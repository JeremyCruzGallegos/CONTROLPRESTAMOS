import {ApiError, base64url, deny, hash, limitedJson, nowSeconds, randomToken, same, validToken, verifyGoogleIdToken} from "./security.mjs";
import {readAuthorizedLoans, readCatalog, todayLima, dateValue} from "./sheets.mjs";
import {publicPage} from "./privacy.mjs";
import {SheetsRegistry, purgeExpired} from "./registry.mjs";
import {ClientDirectory, importClientDirectory, newCustomerCode, CLIENT_CODE} from "./directory.mjs";

const APP_CALLBACK = "cpconsultas://oauth/callback";
const LENDER_CALLBACK = "com.example.controlprestamos.consultasadmin://oauth/callback";
const MONTH = 30 * 86400;
const HEADERS = {"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer"};
function json(value, status = 200) { return new Response(JSON.stringify(value), {status, headers: {...HEADERS, "Content-Type": "application/json; charset=utf-8"}}); }
function redirect(location, cookie = null) { return new Response(null, {status: 302, headers: {...HEADERS, Location: location, ...(cookie ? {"Set-Cookie": cookie} : {})}}); }
function cookieValue(request) { return (request.headers.get("Cookie") || "").split(";").map(value => value.trim()).find(value => value.startsWith("__Host-cp_oauth="))?.slice(16) || ""; }
function configuration(env) {
  let origin; try { const url = new URL(env.PUBLIC_ORIGIN); if (url.protocol !== "https:" || url.origin !== env.PUBLIC_ORIGIN || url.username || url.password) throw new Error(); origin = url.origin; } catch { throw new ApiError(503, "SETUP_REQUIRED", "El prestamista aún está preparando el servicio de consultas."); }
  if (!/^\d+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$/.test(env.GOOGLE_CLIENT_ID || "") || !env.GOOGLE_CLIENT_SECRET || !env.SHEETS_SERVICE_ACCOUNT_JSON || !env.ADMIN_EMAIL ||
      !/^[A-Za-z0-9_-]{20,100}$/.test(env.SPREADSHEET_ID || "")) throw new ApiError(503, "SETUP_REQUIRED", "El prestamista aún está preparando el servicio de consultas.");
  return origin;
}
function additionalEmails(env) {
  return new Set(String(env.ADMIN_ADDITIONAL_EMAILS || "").split(/[,;\s]+/).map(email => email.trim().toLowerCase()).filter(Boolean));
}
function isAdministrator(state, sub, env) {
  return state.adminSub === sub || Object.entries(state.additionalAdmins || {}).some(([email, boundSub]) => boundSub === sub && additionalEmails(env).has(email));
}
function profile(state, sub, env) {
  const account = state.accounts[sub]; if (!account || account.blocked) throw new ApiError(403, "ACCESS_REVOKED", "El prestamista retiró el acceso de esta cuenta.");
  const customer = state.customers?.[account.customerCode];
  const authorized = !!customer && customer.enabled && customer.linkedSub === sub;
  const loans = authorized ? customer.loanIds.filter(id => state.grants[id]?.sub === sub).sort() : [];
  return {sub: account.sub, email: account.email, displayName: account.display_name, requestCode: account.request_code,
    customerCode: account.customerCode || "", customerName: authorized ? customer.name : "",
    administrator: isAdministrator(state, sub, env), authorized, loanIds: loans, supportEmail: env.SUPPORT_EMAIL || env.ADMIN_EMAIL};
}
function sessionIn(state, tokenHash, env, now, admin = false) {
  const session = state.sessions[tokenHash];
  if (!session || session.expires_at <= now) throw deny("SESSION_EXPIRED", "Tu sesión terminó. Vuelve a conectar con Google.");
  const current = profile(state, session.sub, env);
  if (admin && !current.administrator) throw new ApiError(403, "ADMIN_REQUIRED", "Esta opción corresponde al prestamista.");
  return {sub: session.sub, tokenHash, profile: current};
}
const customerCode = newCustomerCode;
function ensureCustomers(state, now) {
  if (state.customers) return;
  state.customers = {};
  for (const account of Object.values(state.accounts)) {
    const ids = Object.entries(state.grants).filter(([,grant]) => grant.sub === account.sub).map(([id]) => id);
    if (!ids.length) continue;
    const code = customerCode();
    state.customers[code] = {code, name: account.display_name, loanIds: ids, enabled: false, linkedSub: account.sub, created_at: now};
  }
}
function customerList(state) { return Object.values(state.customers || {}).map(item => ({...item,
  email: state.accounts[item.linkedSub]?.email || "", requests: Object.values(state.accounts).filter(account => account.customerCode === item.code)
    .map(account => ({sub: account.sub, email: account.email, name: account.display_name}))})); }
async function authenticate(request, registry, directory, env, now) {
  const bearer = request.headers.get("Authorization") || "";
  if (!bearer.startsWith("Bearer ") || !validToken(bearer.slice(7))) throw deny();
  const tokenHash = await hash(bearer.slice(7));
  const visible = await directory.read();
  return registry.transact(state => {
    ensureCustomers(state, now);
    importClientDirectory(state,visible.entries);
    const current = sessionIn(state, tokenHash, env, now);
    if(state.sessions[tokenHash].client === "customer") state.accounts[current.sub].registeredCustomer = true;
    if (state.sessions[tokenHash].expires_at < now + MONTH - 86400) state.sessions[tokenHash].expires_at = now + MONTH;
    return current;
  });
}

export function createHandler({fetcher = fetch, catalogReader = readCatalog, loansReader = readAuthorizedLoans, clock = nowSeconds, registryFactory = null} = {}) {
  const registries = new WeakMap();
  return async function handle(request, env) {
    let path = "/", stage = "configuration";
    try {
      const url = new URL(request.url); path = url.pathname; const now = clock();
      if ((path === "/" || path === "/privacy") && request.method === "GET") return new Response(publicPage(env, path === "/privacy"), {
        headers: {...HEADERS, "Content-Type": "text/html; charset=utf-8", "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"}});
      if (path === "/health" && request.method === "GET") {
        let ready = true; try { configuration(env); if (!env.SHEETS_SERVICE_ACCOUNT_JSON) ready = false; } catch { ready = false; }
        return json({service: "ControlPrestamos Consultas", version: "1.3.0", storage: "google-sheets", configured: ready});
      }
      const origin = configuration(env);
      if (url.origin !== origin) throw new ApiError(400, "INVALID_ORIGIN", "Dirección de servicio incorrecta.");
      let registry = registries.get(env);
      if (!registry) { registry = registryFactory ? registryFactory(env) : new SheetsRegistry(env, fetcher); registries.set(env, registry); }
      if (path === "/auth/start" && request.method === "GET") {
        const challenge = url.searchParams.get("code_challenge"), clientState = url.searchParams.get("state");
        if (!validToken(challenge) || !validToken(clientState)) throw new ApiError(400, "INVALID_REQUEST", "Inicia la conexión desde la aplicación.");
        const appCallback = url.searchParams.get("app") === "lender-client" ? "com.example.controlprestamos.cliente.consultasadmin://oauth/callback" : url.searchParams.get("app") === "lender" ? LENDER_CALLBACK : APP_CALLBACK;
        const state = randomToken(), cookie = randomToken(), nonce = randomToken(), googleVerifier = randomToken();
        const stateHash = await hash(state), cookieHash = await hash(cookie);
        stage = "google_login_registry";
        await registry.transact(value => { purgeExpired(value, now); value.flows[stateHash] = {
          app_callback: appCallback,
          cookie_hash: cookieHash, challenge, client_state: clientState, nonce, google_verifier: googleVerifier, expires_at: now + 600}; });
        const target = new URL("https://accounts.google.com/o/oauth2/v2/auth");
        for (const [name, value] of Object.entries({client_id: env.GOOGLE_CLIENT_ID, redirect_uri: origin + "/auth/callback",
          response_type: "code", scope: "openid email", state, nonce, prompt: "select_account", access_type: "online",
          code_challenge: await hash(googleVerifier), code_challenge_method: "S256"})) target.searchParams.set(name, value);
        return redirect(target.toString(), `__Host-cp_oauth=${cookie}; Path=/; Max-Age=600; Secure; HttpOnly; SameSite=Lax`);
      }
      if (path === "/auth/callback" && request.method === "GET") {
        const state = url.searchParams.get("state"), cookie = cookieValue(request);
        if (!validToken(state) || !validToken(cookie)) throw deny("INVALID_LOGIN", "La conexión venció. Vuelve a iniciarla desde la app.");
        const stateHash = await hash(state), cookieHash = await hash(cookie);
        const flow = await registry.transact(value => {
          const found = value.flows[stateHash];
          if (!found || found.expires_at <= now || !same(found.cookie_hash, cookieHash)) throw deny("INVALID_LOGIN", "La conexión venció. Vuelve a iniciarla desde la app.");
          delete value.flows[stateHash]; return found;
        });
        if (url.searchParams.has("error")) return redirect((flow.app_callback || APP_CALLBACK) + "?state=" + encodeURIComponent(flow.client_state) + "&error=cancelled",
          "__Host-cp_oauth=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax");
        const code = url.searchParams.get("code"); if (!code || code.length > 4096) throw deny();
        const tokenResponse = await fetcher("https://oauth2.googleapis.com/token", {method: "POST", redirect: "manual", body: new URLSearchParams({
          client_id: env.GOOGLE_CLIENT_ID, client_secret: env.GOOGLE_CLIENT_SECRET, code, grant_type: "authorization_code",
          redirect_uri: origin + "/auth/callback", code_verifier: flow.google_verifier})});
        if (!tokenResponse.ok) throw deny("GOOGLE_REJECTED", "Google no completó la conexión. Reintenta desde la aplicación.");
        const tokens = await tokenResponse.json();
        const identity = await verifyGoogleIdToken(tokens.id_token, env.GOOGLE_CLIENT_ID, flow.nonce, fetcher, now);
        const requestCode = base64url(crypto.getRandomValues(new Uint8Array(9))).toUpperCase();
        const handoff = randomToken(), codeHash = await hash(handoff);
        await registry.transact(value => {
          if (value.accounts[identity.sub]?.blocked) throw new ApiError(403, "ACCESS_REVOKED", "El prestamista retiró el acceso de esta cuenta.");
          const previous = value.accounts[identity.sub];
          ensureCustomers(value, now);
          value.accounts[identity.sub] = {...previous, sub: identity.sub, email: identity.email, display_name: identity.name,
            request_code: previous?.request_code || requestCode, created_at: previous?.created_at || now, blocked: false, registeredCustomer: previous?.registeredCustomer === true || (flow.app_callback || APP_CALLBACK) === APP_CALLBACK};
          if (!value.adminSub && identity.email === String(env.ADMIN_EMAIL).trim().toLowerCase() && (identity.email.endsWith("@gmail.com") || identity.hd)) value.adminSub = identity.sub;
          if (additionalEmails(env).has(identity.email) && (identity.email.endsWith("@gmail.com") || identity.hd)) {
            value.additionalAdmins ||= {};
            // Bind each expressly configured account once; a reused email cannot
            // silently replace its previously authenticated Google identity.
            value.additionalAdmins[identity.email] ||= identity.sub;
          }
          purgeExpired(value, now); value.handoffs[codeHash] = {sub: identity.sub, challenge: flow.challenge, app_callback: flow.app_callback || APP_CALLBACK, expires_at: now + 120};
        });
        // The Google ID token and service-account credentials never reach a URL or the APK.
        return redirect((flow.app_callback || APP_CALLBACK) + "?code=" + handoff + "&state=" + flow.client_state,
          "__Host-cp_oauth=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax");
      }
      if (path === "/auth/exchange" && request.method === "POST") {
        const body = await limitedJson(request, 2048);
        if (!validToken(body.code) || !validToken(body.verifier)) throw deny();
        const codeHash = await hash(body.code), challenge = await hash(body.verifier), sessionToken = randomToken(), tokenHash = await hash(sessionToken);
        const current = await registry.transact(value => {
          const handoff = value.handoffs[codeHash];
          if (!handoff || handoff.expires_at <= now || !same(handoff.challenge, challenge)) throw deny("INVALID_LOGIN", "La conexión venció. Vuelve a iniciarla desde la aplicación.");
          const account = profile(value, handoff.sub, env); delete value.handoffs[codeHash];
          value.sessions[tokenHash] = {sub: handoff.sub, created_at: now, expires_at: now + MONTH, client: handoff.app_callback === LENDER_CALLBACK || handoff.app_callback === "com.example.controlprestamos.cliente.consultasadmin://oauth/callback" ? "lender" : "customer"}; return account;
        });
        return json({sessionToken, sessionExpiresAt: now + MONTH, profile: current});
      }
      if (!path.startsWith("/v1/")) throw new ApiError(404, "NOT_FOUND", "Esta dirección no está disponible.");
      const directory = new ClientDirectory(env,fetcher);
      const current = await authenticate(request, registry, directory, env, now);
      if (path === "/v1/customer/claim" && request.method === "POST") {
        const body = await limitedJson(request,2048);
        const code = typeof body.code === "string" ? body.code.trim().toUpperCase().replace(/^(?:EL NOMBRE DE USUARIO ES|EL C[ÓO]DIGO DE CLIENTE ES):\s*/, "") : "";
        if (!CLIENT_CODE.test(code)) throw new ApiError(400,"INVALID_CUSTOMER_CODE","Revisa el código proporcionado por el prestamista.");
        const result = await registry.transact(value => {
          const actor = sessionIn(value,current.tokenHash,env,now);
          const account = value.accounts[actor.sub];
          if (account.claimAfter > now) throw new ApiError(429,"CLAIM_RATE_LIMIT","Espera un minuto antes de volver a ingresar un código.");
          // Store a fixed backoff even for an invalid code; return the error after commit.
          account.claimAfter = now + 60;
          const customer = value.customers?.[code];
          if (!customer || customer.linkedSub && customer.linkedSub !== actor.sub) return null;
          if (Object.values(value.customers).some(other => other.code !== code && other.linkedSub === actor.sub)) return null;
          // The code is privately supplied by the lender. Bind it to the first
          // verified Google account, but reveal nothing until the lender enables it.
          customer.linkedSub ||= actor.sub;
          account.customerCode = code;
          for (const id of customer.loanIds) value.grants[id] = {sub: actor.sub, granted_at: now};
          return profile(value, actor.sub, env);
        });
        if (!result) throw new ApiError(403,"CUSTOMER_CODE_UNAVAILABLE","El código no está disponible para esta cuenta. Consulta al prestamista.");
        const claimed=(await registry.read()).state.customers[code];
        await directory.mirror(claimed,result.email);
        return json({profile:result});
      }
      if (path === "/v1/payment-instructions" && request.method === "GET") {
        const value=(await registry.read()).state; const actor=sessionIn(value,current.tokenHash,env,now);
        return json({instructions: actor.profile.authorized ? value.paymentInstructions || "El prestamista aún no ha publicado las instrucciones de pago." : ""});
      }
      if (path === "/v1/session" && request.method === "GET") return json({profile: current.profile});
      if (path === "/v1/logout" && request.method === "POST") { await registry.transact(value => { delete value.sessions[current.tokenHash]; }); return json({ok: true}); }
      if (path === "/v1/loans" && request.method === "GET") {
        // Read only the server-side assignment. Query/body loan IDs and email never grant access.
        const allowed = current.profile.loanIds;
        if (!allowed.length) return json({profile: current.profile, loans: [], asOf: todayLima(), fetchedAt: new Date(now * 1000).toISOString()});
        const loans = await loansReader(env, allowed, fetcher);
        // Recheck access after the Google request: revocation or reassignment during a slow read wins.
        const latest = sessionIn((await registry.read()).state, current.tokenHash, env, now).profile; const authorized = new Set(latest.loanIds);
        return json({profile: latest, loans: loans.filter(loan => authorized.has(loan.id)), asOf: todayLima(), fetchedAt: new Date(now * 1000).toISOString()});
      }
      if (path.startsWith("/v1/admin/")) {
        if (!current.profile.administrator) throw new ApiError(403, "ADMIN_REQUIRED", "Esta opción corresponde al prestamista.");
        if (request.method !== "GET" && (await registry.read()).state.sessions[current.tokenHash]?.client !== "lender")
          throw new ApiError(403,"LENDER_APP_REQUIRED","Autoriza este cambio desde Clientes y acceso en la app del prestamista.");
        if (path === "/v1/admin/customer-for-loans" && request.method === "POST") {
          const body=await limitedJson(request);
          if(typeof body.name!=="string" || !body.name.trim() || body.name.length>200 || !Array.isArray(body.loanIds) || !body.loanIds.length || body.loanIds.length>100 ||
            new Set(body.loanIds).size!==body.loanIds.length || body.loanIds.some(id=>typeof id!=="string" || !/^20\d{2}-\d{3,}$/.test(id))) throw new ApiError(400,"INVALID_CUSTOMER","Revisa los préstamos del cliente.");
          const catalog=await catalogReader(env,fetcher);const valid=new Set(catalog.loans.filter(item=>!item.deleted).map(item=>item.id));
          if(body.loanIds.some(id=>!valid.has(id)))throw new ApiError(409,"LOAN_CHANGED","Actualiza Préstamos antes de abrir el código.");
          const firstDate=catalog.loans.filter(item=>body.loanIds.includes(item.id)).map(item=>item.row?.[6]).filter(value=>value!=null).map(dateValue).sort()[0] || todayLima();
          const newCode=customerCode(body.name,firstDate);
          const customer=await registry.transact(value=>{
            sessionIn(value,current.tokenHash,env,now,true);
            const matches=Object.values(value.customers).filter(item=>body.loanIds.some(id=>item.loanIds.includes(id)));
            if(matches.length>1)throw new ApiError(409,"CUSTOMER_CONFLICT","Los préstamos de este nombre tienen códigos diferentes. Revisa las vinculaciones antes de continuar.");
            let record=matches[0];
            if(!record){record={code:newCode,name:body.name.trim(),loanIds:[],enabled:false,linkedSub:null,created_at:now};value.customers[newCode]=record;}
            record.name=body.name.trim();
            record.loanIds=[...new Set([...record.loanIds,...body.loanIds])].sort();
            if(record.loanIds.length>100)throw new ApiError(400,"TOO_MANY_LOANS","Este cliente alcanzó el límite de préstamos vinculados.");
            if(record.linkedSub)for(const id of record.loanIds)value.grants[id]={sub:record.linkedSub,granted_at:now};
            return {...record,email:value.accounts[record.linkedSub]?.email || "",requests:[]};
          });await directory.mirror(customer,customer.email);return json({customer});
        }
        if (path === "/v1/admin/customers" && request.method === "GET") {
          const value=(await registry.read()).state;sessionIn(value,current.tokenHash,env,now,true);
          return json({customers:customerList(value), paymentInstructions:value.paymentInstructions || ""});
        }
        if (path === "/v1/admin/customers" && request.method === "POST") {
          const body=await limitedJson(request,4096); const name=typeof body.name === "string" ? body.name.trim() : "";
          if (!name || name.length>200) throw new ApiError(400,"INVALID_CUSTOMER","Ingresa el nombre del cliente.");
          const code=customerCode();
          const customer=await registry.transact(value=>{
            sessionIn(value,current.tokenHash,env,now,true);
            const record={code,name,loanIds:[],enabled:false,linkedSub:null,created_at:now};
            if (value.customers[code]) throw new ApiError(409,"CODE_COLLISION","Reintenta crear el cliente.");
            value.customers[code]=record;return record;
          });return json({customer},201);
        }
        if (path === "/v1/admin/customers" && request.method === "PUT") {
          const body=await limitedJson(request);
          if (typeof body.code!=="string" || typeof body.name!=="string" || !body.name.trim() || body.name.length>200 || typeof body.enabled!=="boolean" ||
            !Array.isArray(body.loanIds) || body.loanIds.length>100 || new Set(body.loanIds).size!==body.loanIds.length || body.loanIds.some(id=>typeof id!=="string" || !/^20\d{2}-\d{3,}$/.test(id)) ||
            body.linkedSub!=null && (typeof body.linkedSub!=="string" || !/^\d{5,255}$/.test(body.linkedSub))) throw new ApiError(400,"INVALID_CUSTOMER","Revisa los datos y préstamos del cliente.");
          if (body.loanIds.length) { const catalog=await catalogReader(env,fetcher);const ids=new Set(catalog.loans.filter(item=>!item.deleted).map(item=>item.id));
            if (body.loanIds.some(id=>!ids.has(id))) throw new ApiError(409,"LOAN_CHANGED","Actualiza la lista de préstamos."); }
          await registry.transact(value=>{
            sessionIn(value,current.tokenHash,env,now,true);
            const customer=value.customers[body.code];if(!customer)throw new ApiError(404,"CUSTOMER_NOT_FOUND","No se encontró al cliente.");
            if (body.linkedSub && (!value.accounts[body.linkedSub] || value.accounts[body.linkedSub].blocked)) throw new ApiError(400,"ACCOUNT_REQUIRED","Selecciona la cuenta Google del cliente antes de autorizar.");
            if (Object.values(value.customers).some(other=>other.code!==body.code && (body.loanIds.some(id=>other.loanIds.includes(id)) || body.linkedSub && other.linkedSub===body.linkedSub))) throw new ApiError(409,"CUSTOMER_CONFLICT","La cuenta o un préstamo ya pertenece a otro código de cliente.");
            for(const id of customer.loanIds)delete value.grants[id];
            customer.name=body.name.trim();customer.loanIds=[...body.loanIds];customer.enabled=body.enabled;customer.linkedSub=body.linkedSub || null;
            if(customer.linkedSub)for(const id of customer.loanIds)value.grants[id]={sub:customer.linkedSub,granted_at:now};
          });const record=(await registry.read()).state.customers[body.code];await directory.mirror(record,record.linkedSub?(await registry.read()).state.accounts[record.linkedSub]?.email || "":"");return json({ok:true});
        }
        if (path === "/v1/admin/payment-instructions" && request.method === "PUT") {
          const body=await limitedJson(request,16000);
          if(typeof body.instructions!=="string" || body.instructions.length>4000)throw new ApiError(400,"INVALID_INSTRUCTIONS","Usa instrucciones de hasta 4000 caracteres.");
          await registry.transact(value=>{sessionIn(value,current.tokenHash,env,now,true);value.paymentInstructions=body.instructions.trim();});return json({ok:true});
        }
        if (path === "/v1/admin/accounts" && request.method === "GET") {
          const value = (await registry.read()).state; sessionIn(value, current.tokenHash, env, now, true);
          const accounts = Object.values(value.accounts).sort((a, b) => b.created_at - a.created_at).slice(0, 500);
          return json({accounts: accounts.filter(item => item.sub !== current.sub).map(item => ({
            sub: item.sub, email: item.email, displayName: item.display_name, requestCode: item.request_code,
            blocked: item.blocked, loanIds: Object.entries(value.grants).filter(([, grant]) => grant.sub === item.sub).map(([id]) => id)}))});
        }
        if (path === "/v1/admin/catalog" && request.method === "GET") {
          const catalog = await catalogReader(env, fetcher);
          return json({loans: catalog.loans.filter(item => !item.deleted).map(item => ({id: item.id, clientName: item.clientName}))});
        }
        if (path === "/v1/admin/access" && request.method === "PUT") {
          const body = await limitedJson(request);
          if (typeof body.sub !== "string" || body.sub === current.sub || !Array.isArray(body.loanIds) || body.loanIds.length > 100 ||
              body.loanIds.some(id => typeof id !== "string" || !/^20\d{2}-\d{3,}$/.test(id)) || new Set(body.loanIds).size !== body.loanIds.length || typeof body.blocked !== "boolean") throw new ApiError(400, "INVALID_ASSIGNMENT", "Revisa la cuenta y los préstamos seleccionados.");
          if (body.blocked && body.loanIds.length) throw new ApiError(400, "INVALID_ASSIGNMENT", "Retira los préstamos antes de bloquear la cuenta.");
          if (body.loanIds.length) {
            const catalog = await catalogReader(env, fetcher); const valid = new Set(catalog.loans.filter(item => !item.deleted).map(item => item.id));
            if (body.loanIds.some(id => !valid.has(id))) throw new ApiError(409, "LOAN_CHANGED", "Un préstamo cambió. Actualiza la lista.");
          }
          await registry.transact(value => {
            sessionIn(value, current.tokenHash, env, now, true);
            if (!value.accounts[body.sub]) throw new ApiError(404, "ACCOUNT_NOT_FOUND", "El cliente debe iniciar sesión primero en la app.");
            if (body.loanIds.some(id => value.grants[id] && value.grants[id].sub !== body.sub)) throw new ApiError(409, "LOAN_ALREADY_LINKED", "Un préstamo ya está vinculado a otra cuenta. Revisa sus permisos primero.");
            for (const [id, grant] of Object.entries(value.grants)) if (grant.sub === body.sub) delete value.grants[id];
            value.accounts[body.sub].blocked = body.blocked;
            const customer = Object.values(value.customers || {}).find(item => item.linkedSub === body.sub);
            if (customer) { customer.loanIds = [...body.loanIds]; customer.enabled = !body.blocked; }
            for (const id of body.loanIds) value.grants[id] = {sub: body.sub, granted_at: now};
            if (body.blocked) for (const kind of ["sessions", "handoffs"]) for (const [id, session] of Object.entries(value[kind])) if (session.sub === body.sub) delete value[kind][id];
          });
          return json({ok: true});
        }
      }
      throw new ApiError(404, "NOT_FOUND", "Esta operación no está disponible.");
    } catch (error) {
      // Log only fixed diagnostic fields, never credentials, Google responses,
      // request URLs, account identities, or exception messages.
      const allowedNames = new Set(["TypeError", "ReferenceError", "SyntaxError", "DataError", "OperationError", "InvalidCharacterError", "Error"]);
      console.error(JSON.stringify({event: "consultas_failure", stage,
        code: error instanceof ApiError ? error.code : "UNEXPECTED_FAILURE",
        kind: allowedNames.has(error?.name) ? error.name : "OtherError",
        googleStatus: Number.isInteger(error?.googleStatus) ? error.googleStatus : null}));
      if (error instanceof ApiError) {
        if (path === "/auth/callback") return new Response(error.message + " Regresa a la app para reintentar.", {status: error.status, headers: {...HEADERS, "Content-Type": "text/plain; charset=utf-8"}});
        return json({error: {code: error.code, message: error.message}}, error.status);
      }
      // Do not return Google API errors, SQL, request credentials, or other borrowers' rows.
      return json({error: {code: "SERVICE_UNAVAILABLE", message: "El servicio no está disponible en este momento. Reintenta más tarde."}}, 503);
    }
  };
}
export default {fetch: createHandler()};
