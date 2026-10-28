import {test} from "node:test";
import assert from "node:assert/strict";
import {claims, audience, fixedNow, signedToken, keysFetch, jwk} from "./helpers.mjs";
import {verifyGoogleIdToken, hash, limitedJson} from "../src/security.mjs";

test("accepts a Google signature with the expected identity, audience, nonce and lifetime", async () => {
  const identity = await verifyGoogleIdToken(await signedToken(claims()), audience, "test-nonce", keysFetch, fixedNow);
  assert.equal(identity.sub, "100000000001"); assert.equal(identity.email, "ana@gmail.com");
});
for (const [label, changes] of Object.entries({audience: {aud: "another-app"}, issuer: {iss: "https://attacker.invalid"}, expired: {exp: fixedNow},
  future: {iat: fixedNow + 31}, notYetValid: {nbf: fixedNow + 31}, nonce: {nonce: "wrong"}, unverifiedEmail: {email_verified: false}, missingSubject: {sub: ""}})) {
  test("rejects ID token with invalid " + label, async () => {
    await assert.rejects(() => signedToken(claims(changes)).then(token => verifyGoogleIdToken(token, audience, "test-nonce", keysFetch, fixedNow)));
  });
}
test("rejects a signature modified after Google signs it", async () => {
  const token = await signedToken(claims()); const [header, payload, signature] = token.split('.');
  const changed = (signature[0] === 'A' ? 'B' : 'A') + signature.slice(1);
  await assert.rejects(() => verifyGoogleIdToken(`${header}.${payload}.${changed}`, audience, "test-nonce", keysFetch, fixedNow));
});
test("does not accept alg none or a different algorithm", async () => {
  for (const alg of ["none", "HS256"]) await assert.rejects(() => signedToken(claims(), {alg, kid: "google-test-key"}).then(token => verifyGoogleIdToken(token, audience, "test-nonce", keysFetch, fixedNow)));
});
test("PKCE matches the RFC 7636 published vector", async () => {
  assert.equal(await hash("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
});
test("rejects oversized JSON even without a Content-Length header", async () => {
  const request = new Request("https://service.invalid", {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({data: "x".repeat(3000)})});
  await assert.rejects(() => limitedJson(request, 1000), error => error.status === 413);
});
test("refreshes Google public keys on rotation without failing a valid new login", async () => {
  let calls = 0;
  const rotatingKeys = async () => new Response(JSON.stringify({keys: [{...jwk, kid: ++calls === 1 ? "old-google-key" : "new-google-key"}]}), {headers: {"Cache-Control": "max-age=3600"}});
  await verifyGoogleIdToken(await signedToken(claims(), {alg: "RS256", kid: "old-google-key"}), audience, "test-nonce", rotatingKeys, fixedNow);
  await verifyGoogleIdToken(await signedToken(claims(), {alg: "RS256", kid: "new-google-key"}), audience, "test-nonce", rotatingKeys, fixedNow);
  assert.equal(calls, 2);
});
