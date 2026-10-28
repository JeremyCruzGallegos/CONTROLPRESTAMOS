import {webcrypto} from "node:crypto";
import {base64url} from "../src/security.mjs";

if (!globalThis.crypto) globalThis.crypto = webcrypto;
export const signingKey = await crypto.subtle.generateKey({name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256"}, true, ["sign", "verify"]);
export const jwk = {...await crypto.subtle.exportKey("jwk", signingKey.publicKey), kid: "google-test-key", alg: "RS256", use: "sig"};
const privateBytes = new Uint8Array(await crypto.subtle.exportKey("pkcs8", signingKey.privateKey));
export const serviceCredentials = JSON.stringify({client_email: "reader@fixture.iam.gserviceaccount.com", private_key:
  "-----BEGIN PRIVATE KEY-----\n" + btoa(String.fromCharCode(...privateBytes)) + "\n-----END PRIVATE KEY-----"});
export async function signedToken(claims, header = {alg: "RS256", kid: jwk.kid}) {
  const text = new TextEncoder(); const data = base64url(text.encode(JSON.stringify(header))) + "." + base64url(text.encode(JSON.stringify(claims)));
  return data + "." + base64url(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", signingKey.privateKey, text.encode(data)));
}
export const audience = "123456789012-consultasfixture.apps.googleusercontent.com";
export const fixedNow = 1793124000;
export function claims(overrides = {}) { return {iss: "https://accounts.google.com", aud: audience, sub: "100000000001", email: "ana@gmail.com", name: "Ana", email_verified: true, iat: fixedNow - 10, exp: fixedNow + 3600, nonce: "test-nonce", ...overrides}; }
export async function keysFetch() { return new Response(JSON.stringify({keys: [jwk]}), {headers: {"Cache-Control": "max-age=3600"}}); }
