import {test} from "node:test";
import assert from "node:assert/strict";
import {audience, fixedNow, claims, signedToken, keysFetch, serviceCredentials} from "./helpers.mjs";
import {createHandler} from "../src/worker.mjs";
import {hash, randomToken} from "../src/security.mjs";
import {SheetsRegistry} from "../src/registry.mjs";
import {FakeSheets} from "./fake-sheets.mjs";

const origin = "https://consultas.example.workers.dev";
const A = "100000000001", B = "200000000002", ADMIN = "900000000009";
const LOAN_A = "2026-001", LOAN_B = "2026-002";
async function fixture(t, options = {}) {
  const env = {PUBLIC_ORIGIN: origin, GOOGLE_CLIENT_ID: audience, GOOGLE_CLIENT_SECRET: "fixture-secret-not-real",
    ADMIN_EMAIL: "prestamista@gmail.com", SUPPORT_EMAIL: "support@example.invalid", SHEETS_SERVICE_ACCOUNT_JSON: serviceCredentials,
    SPREADSHEET_ID: "YOUR_SPREADSHEET_ID_REPLACE_ME"};
  const sheets = new FakeSheets(env.SPREADSHEET_ID);
  const {fetcher: identityFetch = keysFetch, ...overrides} = options;
  const fetcher = async (url, params) => await sheets.fetch(url, params) || identityFetch(url, params);
  const registry = new SheetsRegistry(env, fetcher); env.TEST_REGISTRY = registry;
  const bearer = {[A]: randomToken(), [B]: randomToken(), [ADMIN]: randomToken()};
  const hashes = Object.fromEntries(await Promise.all(Object.entries(bearer).map(async ([sub, token]) => [sub, await hash(token)])));
  await registry.transact(value => {
    for (const [sub, email] of [[A, "ana@gmail.com"], [B, "bruno@gmail.com"], [ADMIN, "prestamista@gmail.com"]]) {
      value.accounts[sub] = {sub, email, display_name: email, request_code: sub, created_at: fixedNow, blocked: false};
      value.sessions[hashes[sub]] = {sub, created_at: fixedNow, expires_at: fixedNow + 3600, client: "lender"};
    }
    value.customers = {};
    for(const [sub,id,n] of [[A,LOAN_A,'A'],[B,LOAN_B,'B']]) {
      const code='CP-'+n.repeat(20);value.accounts[sub].customerCode=code;
      value.customers[code]={code,name:value.accounts[sub].display_name,loanIds:[id],enabled:true,linkedSub:sub,created_at:fixedNow};
    }
    value.adminSub = ADMIN; value.grants[LOAN_A] = {sub: A, granted_at: fixedNow}; value.grants[LOAN_B] = {sub: B, granted_at: fixedNow};
  });
  const handler = createHandler({clock: () => fixedNow,
    catalogReader: async () => ({loans: [{id: LOAN_A, clientName: "Ana", deleted: false}, {id: LOAN_B, clientName: "Bruno", deleted: false}]}),
    loansReader: async (_env, ids) => ids.map(id => ({id, confidential: id === LOAN_A ? "ANA_ONLY" : "BRUNO_ONLY"})),
    ...overrides, fetcher, registryFactory: () => registry});
  const request = async (path, sub = A, method = "GET", body = null) => handler(new Request(origin + path, {method,
    headers: {...(sub ? {Authorization: "Bearer " + bearer[sub]} : {}), ...(body ? {"Content-Type": "application/json"} : {})},
    ...(body ? {body: JSON.stringify(body)} : {})}), env);
  return {registry, sheets, env, bearer, hashes, handler, request};
}
test("a customer can receive only its assigned loans even when supplying another email and loan ID", async t => {
  const f = await fixture(t);
  const response = await f.request(`/v1/loans?loanId=${LOAN_B}&email=bruno@gmail.com`);
  assert.equal(response.status, 200); assert.equal(response.headers.get("Cache-Control"), "no-store");
  const text = await response.text(); assert.ok(text.includes("ANA_ONLY")); assert.ok(!text.includes("BRUNO_ONLY")); assert.ok(!text.includes(LOAN_B));
  const other = await (await f.request("/v1/loans", B)).json(); assert.deepEqual(other.loans.map(item => item.id), [LOAN_B]);
});
test("requires a session and prevents borrower access to all administrator endpoints", async t => {
  const f = await fixture(t);
  assert.equal((await f.request("/v1/loans", null)).status, 401);
  for (const path of ["/v1/admin/accounts", "/v1/admin/catalog"]) assert.equal((await f.request(path)).status, 403);
  assert.equal((await f.request("/v1/admin/access", A, "PUT", {sub: B, loanIds: [LOAN_B], blocked: false})).status, 403);
});
test("has no financial mutation endpoint for borrowers or administrators", async t => {
  const f = await fixture(t);
  for (const method of ["POST", "PUT", "DELETE"]) for (const sub of [A, ADMIN]) assert.equal((await f.request("/v1/loans", sub, method, {paid: true})).status, 404);
});
test("rechecks assignments after a slow Sheets query so a concurrent revocation wins", async t => {
  const f = await fixture(t, {loansReader: async env => {
    await env.TEST_REGISTRY.transact(value => { delete value.grants[LOAN_A]; });
    return [{id: LOAN_A, confidential: "DO_NOT_RETURN"}, {id: LOAN_B, confidential: "OTHER_ACCOUNT"}];
  }});
  const body = await (await f.request("/v1/loans")).json(); assert.deepEqual(body.loans, []);
});
test("blocked accounts lose existing sessions and handoff codes immediately", async t => {
  const f = await fixture(t);
  const codeHash = await hash(randomToken()), challenge = await hash(randomToken());
  await f.registry.transact(value => { value.handoffs[codeHash] = {sub: A, challenge, expires_at: fixedNow + 100}; });
  assert.equal((await f.request("/v1/admin/access", ADMIN, "PUT", {sub: A, loanIds: [], blocked: true})).status, 200);
  assert.equal((await f.request("/v1/loans")).status, 401);
  const value = (await f.registry.read()).state;
  assert.ok(!Object.values(value.handoffs).some(item => item.sub === A));
  assert.ok(!Object.values(value.grants).some(item => item.sub === A));
});
test("cannot silently assign a loan already assigned to another account", async t => {
  const f = await fixture(t);
  const response = await f.request("/v1/admin/access", ADMIN, "PUT", {sub: A, loanIds: [LOAN_A, LOAN_B], blocked: false});
  assert.equal(response.status, 409);
  const grants = (await f.registry.read()).state.grants; assert.deepEqual(Object.keys(grants).filter(id => grants[id].sub === A), [LOAN_A]);
});
test("expired or logged-out sessions are unusable", async t => {
  const f = await fixture(t);
  assert.equal((await f.request("/v1/logout", A, "POST")).status, 200);
  assert.equal((await f.request("/v1/session")).status, 401);
  await f.registry.transact(value => { value.sessions[f.hashes[B]].expires_at = fixedNow; });
  assert.equal((await f.request("/v1/session", B)).status, 401);
});
test("a PKCE handoff is single-use and a wrong verifier cannot consume it", async t => {
  const f = await fixture(t); const verifier = randomToken(), code = randomToken();
  const codeHash = await hash(code), challenge = await hash(verifier);
  await f.registry.transact(value => { value.handoffs[codeHash] = {sub: A, challenge, expires_at: fixedNow + 100}; });
  assert.equal((await f.request("/auth/exchange", null, "POST", {code, verifier: randomToken()})).status, 401);
  const good = await f.request("/auth/exchange", null, "POST", {code, verifier}); assert.equal(good.status, 200);
  const body = await good.json(); assert.equal(body.profile.sub, A); assert.match(body.sessionToken, /^[A-Za-z0-9_-]{43}$/);
  assert.equal((await f.request("/auth/exchange", null, "POST", {code, verifier})).status, 401);
});
test("complete browser Google login binds state, cookie, Google nonce and both PKCE challenges", async t => {
  let googleNonce, googleChallenge;
  const fakeFetch = async (url, options) => {
    if (String(url).includes("/certs")) return keysFetch();
    assert.equal(String(url), "https://oauth2.googleapis.com/token");
    assert.equal(options.body.get("client_secret"), "fixture-secret-not-real");
    assert.equal(await hash(options.body.get("code_verifier")), googleChallenge);
    return new Response(JSON.stringify({id_token: await signedToken(claims({nonce: googleNonce}))}));
  };
  const f = await fixture(t, {fetcher: fakeFetch}); const verifier = randomToken(), state = randomToken();
  const start = await f.request(`/auth/start?state=${state}&code_challenge=${await hash(verifier)}`, null);
  assert.equal(start.status, 302);
  const target = new URL(start.headers.get("Location")); assert.equal(target.origin, "https://accounts.google.com"); assert.equal(target.searchParams.get("scope"), "openid email");
  googleNonce = target.searchParams.get("nonce"); googleChallenge = target.searchParams.get("code_challenge");
  const cookie = start.headers.get("Set-Cookie").split(';')[0]; assert.ok(cookie.includes("__Host-cp_oauth="));
  const callbackUrl = origin + "/auth/callback?code=google-code&state=" + target.searchParams.get("state");
  const missingCookie = await f.handler(new Request(callbackUrl), f.env); assert.equal(missingCookie.status, 401);
  const callback = await f.handler(new Request(callbackUrl, {headers: {Cookie: cookie}}), f.env); assert.equal(callback.status, 302);
  const destination = new URL(callback.headers.get("Location")); assert.equal(destination.protocol, "cpconsultas:"); assert.equal(destination.searchParams.get("state"), state);
  assert.ok(!destination.toString().includes("id_token"));
  const exchange = await f.request("/auth/exchange", null, "POST", {code: destination.searchParams.get("code"), verifier}); assert.equal(exchange.status, 200);
  assert.equal((await exchange.json()).profile.sub, A);
  assert.equal((await f.handler(new Request(callbackUrl, {headers: {Cookie: cookie}}), f.env)).status, 401);
});
test("an unlinked customer receives a request code and no financial records", async t => {
  const f = await fixture(t); await f.registry.transact(value => { delete value.grants[LOAN_A]; delete value.accounts[A].customerCode; });
  const response = await f.request("/v1/loans"); const body = await response.json();
  assert.deepEqual(body.loans, []); assert.equal(body.profile.authorized, false); assert.equal(body.profile.requestCode, A);
});
test("changing the configured spreadsheet cannot reuse permissions for matching loan IDs in another book", async t => {
  const f = await fixture(t); assert.equal((await f.request("/v1/loans")).status, 200);
  f.env.SPREADSHEET_ID = "another-spreadsheet-with-same-loan-ids";
  const response = await f.request("/v1/loans"); assert.equal(response.status, 503);
  const failure=await response.json();assert.ok(["BOOK_CONFIGURATION_CHANGED","BOOK_NEEDS_REVIEW"].includes(failure.error.code));assert.equal(failure.loans,undefined);
});
test("an expired handoff cannot create a session", async t => {
  const f = await fixture(t); const verifier = randomToken(), code = randomToken();
  const codeHash = await hash(code), challenge = await hash(verifier);
  await f.registry.transact(value => { value.handoffs[codeHash] = {sub: A, challenge, expires_at: fixedNow}; });
  assert.equal((await f.request("/auth/exchange", null, "POST", {code, verifier})).status, 401);
});
test("first administrator is bound to Google subject and changing the configured email cannot transfer administration", async t => {
  let googleNonce, identity = {sub: ADMIN, email: "prestamista@gmail.com"};
  const fakeFetch = async url => String(url).includes("/certs") ? keysFetch() : new Response(JSON.stringify({id_token: await signedToken(claims({...identity, nonce: googleNonce}))}));
  const f = await fixture(t, {fetcher: fakeFetch}); await f.registry.transact(value => { value.adminSub = null; });
  async function googleLogin() {
    const verifier = randomToken(), state = randomToken();
    const start = await f.request(`/auth/start?state=${state}&code_challenge=${await hash(verifier)}`, null);
    const target = new URL(start.headers.get("Location")); googleNonce = target.searchParams.get("nonce");
    const result = await f.handler(new Request(origin + "/auth/callback?code=test&state=" + target.searchParams.get("state"), {
      headers: {Cookie: start.headers.get("Set-Cookie").split(';')[0]}}), f.env);
    const destination = new URL(result.headers.get("Location"));
    return (await (await f.request("/auth/exchange", null, "POST", {code: destination.searchParams.get("code"), verifier})).json()).profile;
  }
  assert.equal((await googleLogin()).administrator, true);
  assert.equal(f.sheets.cells.get("1:29"), "prestamista@gmail.com");
  f.env.ADMIN_EMAIL = "ana@gmail.com"; identity = {sub: A, email: "ana@gmail.com"};
  assert.equal((await googleLogin()).administrator, false);
  assert.equal((await (await f.request("/v1/session", ADMIN)).json()).profile.administrator, true);
});
test("additional administrator coexists with the lender, binds Google identity, and can be revoked independently", async t => {
  let nonce, identity = {sub: A, email: "ana@gmail.com"};
  const fakeFetch = async url => String(url).includes("/certs") ? keysFetch() : new Response(JSON.stringify({id_token: await signedToken(claims({...identity, nonce}))}));
  const f = await fixture(t, {fetcher: fakeFetch});
  f.env.ADMIN_ADDITIONAL_EMAILS = " ANA@gmail.com ; other@gmail.com ";
  assert.equal((await f.request('/v1/admin/accounts', A)).status,403);
  async function login() {
    const verifier=randomToken();
    const start=await f.request(`/auth/start?state=${randomToken()}&code_challenge=${await hash(verifier)}`,null);
    const target=new URL(start.headers.get('Location'));nonce=target.searchParams.get('nonce');
    const callback=await f.handler(new Request(origin+'/auth/callback?code=test&state='+target.searchParams.get('state'),{headers:{Cookie:start.headers.get('Set-Cookie').split(';')[0]}}),f.env);
    const destination=new URL(callback.headers.get('Location'));
    return (await (await f.request('/auth/exchange',null,'POST',{code:destination.searchParams.get('code'),verifier})).json()).profile;
  }
  assert.equal((await login()).administrator,true);
  assert.equal((await f.request('/v1/admin/accounts',A)).status,200);
  assert.equal((await f.request('/v1/admin/accounts',ADMIN)).status,200);
  assert.equal((await f.registry.read()).state.adminSub,ADMIN);
  identity={sub:B,email:'ana@gmail.com'};
  assert.equal((await login()).administrator,false);
  delete f.env.ADMIN_ADDITIONAL_EMAILS;
  assert.equal((await f.request('/v1/admin/accounts',A)).status,403);
  assert.equal((await f.request('/v1/admin/accounts',ADMIN)).status,200);
});
test("customer code requires Google account binding and reversible lender approval before revealing loans", async t => {
  const f=await fixture(t);
  await f.registry.transact(value=>{delete value.customers[value.accounts[A].customerCode];delete value.accounts[A].customerCode;delete value.grants[LOAN_A];});
  const created=await f.request('/v1/admin/customers',ADMIN,'POST',{name:'Ana cliente'});
  assert.equal(created.status,201);const customer=(await created.json()).customer;
  assert.match(customer.code,/^CP-\d{8}-[A-Z]{1,4}-[0-9A-F]{8}$/);
  assert.equal(customer.enabled,false);
  const body={...customer,loanIds:[LOAN_A],linkedSub:A,enabled:false};
  assert.equal((await f.request('/v1/admin/customers',ADMIN,'PUT',body)).status,200);
  assert.equal((await f.request('/v1/customer/claim',A,'POST',{code:customer.code.toLowerCase()})).status,200);
  assert.deepEqual((await (await f.request('/v1/loans',A)).json()).loans,[]);
  assert.equal((await f.request('/v1/admin/customers',ADMIN,'PUT',{...body,enabled:true})).status,200);
  assert.deepEqual((await (await f.request('/v1/loans',A)).json()).loans.map(item=>item.id),[LOAN_A]);
  assert.equal((await f.request('/v1/admin/customers',ADMIN,'PUT',{...body,enabled:false})).status,200);
  const revoked=await (await f.request('/v1/loans',A)).json();assert.equal(revoked.profile.authorized,false);assert.deepEqual(revoked.loans,[]);
  assert.equal((await f.request('/v1/admin/customers',ADMIN,'PUT',{...body,enabled:true})).status,200);
  assert.equal((await (await f.request('/v1/loans',A)).json()).loans.length,1);
  assert.equal((await f.request('/v1/customer/claim',B,'POST',{code:customer.code})).status,403);
  assert.equal((await f.request('/v1/customer/claim',B,'POST',{code:customer.code})).status,429);
});
test("lender approval without entering a code does not reveal data and customer groups reject duplicate loans", async t => {
  const f=await fixture(t);
  await f.registry.transact(value=>{delete value.accounts[A].customerCode;});
  assert.equal((await (await f.request('/v1/loans',A)).json()).loans.length,0);
  const first=(await f.registry.read()).state.customers['CP-'+'A'.repeat(20)];
  assert.equal((await f.request('/v1/admin/customers',ADMIN,'PUT',{...first,loanIds:[LOAN_A,LOAN_B]})).status,409);
  assert.equal((await f.request('/v1/admin/customers',A,'POST',{name:'Unauthorized'})).status,403);
});
test("one client code can hold several loans and payment instructions remain private until authorized", async t => {
  const f=await fixture(t);
  await f.registry.transact(value=>{
    const a=value.customers['CP-'+'A'.repeat(20)];a.loanIds.push(LOAN_B);
    delete value.customers['CP-'+'B'.repeat(20)];delete value.accounts[B].customerCode;
    value.grants[LOAN_B]={sub:A,granted_at:fixedNow};
  });
  assert.deepEqual((await (await f.request('/v1/loans',A)).json()).loans.map(item=>item.id),[LOAN_A,LOAN_B]);
  assert.equal((await f.request('/v1/admin/payment-instructions',ADMIN,'PUT',{instructions:'Yape: datos de prueba'})).status,200);
  assert.equal((await (await f.request('/v1/payment-instructions',A)).json()).instructions,'Yape: datos de prueba');
  assert.equal((await (await f.request('/v1/payment-instructions',B)).json()).instructions,'');
});
test("lender OAuth callback is fixed by the stored flow and preserves customer code after re-login", async t => {
  let nonce;
  const fakeFetch=async url=>String(url).includes('/certs')?keysFetch():new Response(JSON.stringify({id_token:await signedToken(claims({nonce}))}));
  const f=await fixture(t,{fetcher:fakeFetch});const verifier=randomToken();
  const start=await f.request(`/auth/start?app=lender-client&state=${randomToken()}&code_challenge=${await hash(verifier)}`,null);
  const target=new URL(start.headers.get('Location'));nonce=target.searchParams.get('nonce');
  const callback=await f.handler(new Request(origin+'/auth/callback?code=test&state='+target.searchParams.get('state'),{headers:{Cookie:start.headers.get('Set-Cookie').split(';')[0]}}),f.env);
  assert.ok(callback.headers.get('Location').startsWith('com.example.controlprestamos.cliente.consultasadmin://oauth/callback?'));
  assert.equal((await f.registry.read()).state.accounts[A].customerCode,'CP-'+'A'.repeat(20));
});
test("customer permission revoked during a financial read prevents delivery of the loan",async t=>{
  const f=await fixture(t,{loansReader:async env=>{
    await env.TEST_REGISTRY.transact(value=>{value.customers['CP-'+'A'.repeat(20)].enabled=false;});
    return[{id:LOAN_A,confidential:'REVOKED_DATA'}];
  }});
  const response=await(await f.request('/v1/loans',A)).json();assert.deepEqual(response.loans,[]);assert.equal(response.profile.authorized,false);
});
test("customer app sessions cannot write lender customer assignments even for an administrator",async t=>{
  const f=await fixture(t);
  await f.registry.transact(value=>{value.sessions[f.hashes[ADMIN]].client='customer';});
  const response=await f.request('/v1/admin/customers',ADMIN,'POST',{name:'Should not be created'});
  assert.equal(response.status,403);assert.equal((await response.json()).error.code,'LENDER_APP_REQUIRED');
});
test("legacy grants migrate into disabled client groups without discarding their loans",async t=>{
  const f=await fixture(t);await f.registry.transact(value=>{delete value.customers;delete value.accounts[A].customerCode;delete value.accounts[B].customerCode;});
  const response=await(await f.request('/v1/loans',A)).json();assert.deepEqual(response.loans,[]);
  const value=(await f.registry.read()).state;
  assert.equal(Object.keys(value.customers).length,2);
  const a=Object.values(value.customers).find(item=>item.linkedSub===A);assert.deepEqual(a.loanIds,[LOAN_A]);assert.equal(a.enabled,false);
  assert.equal(value.grants[LOAN_A].sub,A);
});
test("opening an existing loan group creates one stable code and keeps all its loans",async t=>{
  const f=await fixture(t);await f.registry.transact(value=>{value.customers={};value.grants={};delete value.accounts[A].customerCode;delete value.accounts[B].customerCode;});
  const body={name:'Ana',loanIds:[LOAN_A,LOAN_B]};
  const first=await f.request('/v1/admin/customer-for-loans',ADMIN,'POST',body);assert.equal(first.status,200);
  const customer=(await first.json()).customer;assert.equal(customer.enabled,false);assert.deepEqual(customer.loanIds,[LOAN_A,LOAN_B]);
  const again=(await(await f.request('/v1/admin/customer-for-loans',ADMIN,'POST',{name:'Ana',loanIds:[LOAN_A]})).json()).customer;
  assert.equal(again.code,customer.code);assert.deepEqual(again.loanIds,[LOAN_A,LOAN_B]);
  assert.equal(Object.keys((await f.registry.read()).state.customers).length,1);
});
test("checkbox can preauthorize before registration, then the code binds only its first Google account",async t=>{
  const f=await fixture(t);await f.registry.transact(value=>{value.customers={};value.grants={};delete value.accounts[A].customerCode;delete value.accounts[B].customerCode;});
  const customer=(await(await f.request('/v1/admin/customer-for-loans',ADMIN,'POST',{name:'Ana',loanIds:[LOAN_A]})).json()).customer;
  assert.equal((await f.request('/v1/admin/customers',ADMIN,'PUT',{...customer,enabled:true})).status,200);
  assert.deepEqual((await(await f.request('/v1/loans',A)).json()).loans,[]);
  const claim=await f.request('/v1/customer/claim',A,'POST',{code:customer.code});assert.equal(claim.status,200);assert.equal((await claim.json()).profile.authorized,true);
  assert.equal((await(await f.request('/v1/loans',A)).json()).loans.length,1);
  assert.equal((await f.request('/v1/customer/claim',B,'POST',{code:customer.code})).status,403);
});
test("entering a code with checkbox disabled binds identity without revealing finances",async t=>{
  const f=await fixture(t);await f.registry.transact(value=>{value.customers={};value.grants={};delete value.accounts[A].customerCode;delete value.accounts[B].customerCode;});
  const customer=(await(await f.request('/v1/admin/customer-for-loans',ADMIN,'POST',{name:'Ana',loanIds:[LOAN_A]})).json()).customer;
  assert.equal((await f.request('/v1/customer/claim',A,'POST',{code:'El codigo de cliente es:\n'+customer.code})).status,200);
  assert.deepEqual((await(await f.request('/v1/loans',A)).json()).loans,[]);
  const bound=(await f.registry.read()).state.customers[customer.code];assert.equal(bound.linkedSub,A);
  assert.equal((await f.request('/v1/admin/customers',ADMIN,'PUT',{...bound,enabled:true})).status,200);
  assert.equal((await(await f.request('/v1/loans',A)).json()).loans.length,1);
  assert.equal((await f.request('/v1/admin/customers',ADMIN,'PUT',{...bound,enabled:false})).status,200);
  assert.deepEqual((await(await f.request('/v1/loans',A)).json()).loans,[]);
});
test("a name group containing separate client codes fails safely instead of merging people",async t=>{
  const f=await fixture(t);
  const response=await f.request('/v1/admin/customer-for-loans',ADMIN,'POST',{name:'Same display name',loanIds:[LOAN_A,LOAN_B]});
  assert.equal(response.status,409);assert.equal((await response.json()).error.code,'CUSTOMER_CONFLICT');
  assert.equal(Object.keys((await f.registry.read()).state.customers).length,2);
});
test("an existing customer session makes its verified email visible on the next refresh",async t=>{
  const f=await fixture(t);await f.registry.transact(state=>{state.sessions[f.hashes[A]].client='customer';});
  assert.equal((await f.request('/v1/loans',A)).status,200);
  assert.equal(f.sheets.cells.get('1:29'),'ana@gmail.com');assert.equal(f.sheets.cells.get('1:31'),'CP-'+'A'.repeat(20));
});
