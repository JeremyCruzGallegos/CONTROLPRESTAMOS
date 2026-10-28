const encoder = new TextEncoder();
export class ApiError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}
export const deny = (code = "UNAUTHORIZED", message = "Vuelve a iniciar sesión con Google.") => new ApiError(401, code, message);
export const nowSeconds = () => Math.floor(Date.now() / 1000);
export function base64url(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
export function decode64(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value)) throw deny();
  const raw = atob(value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "="));
  return Uint8Array.from(raw, c => c.charCodeAt(0));
}
export const randomToken = () => base64url(crypto.getRandomValues(new Uint8Array(32)));
export const hash = async text => base64url(await crypto.subtle.digest("SHA-256", encoder.encode(text)));
export const validToken = value => typeof value === "string" && /^[A-Za-z0-9_-]{43}$/.test(value);
export function same(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let mismatch = 0; for (let i = 0; i < a.length; i++) mismatch |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return mismatch === 0;
}
const keyCaches = new WeakMap();
async function googleKeys(fetcher, now) {
  const response = await fetcher("https://www.googleapis.com/oauth2/v3/certs", {redirect: "manual"});
  if (!response.ok) throw new ApiError(503, "GOOGLE_UNAVAILABLE", "Google no está disponible. Reintenta en unos momentos.");
  const body = await response.json(); if (!Array.isArray(body.keys)) throw deny();
  const maxAge = Number((response.headers.get("Cache-Control") || "").match(/max-age=(\d+)/)?.[1] || 300);
  const cached = {keys: body.keys, until: now + Math.max(30, Math.min(maxAge, 21600))}; keyCaches.set(fetcher, cached); return cached;
}
export async function verifyGoogleIdToken(token, audience, nonce, fetcher = fetch, now = nowSeconds()) {
  if (typeof token !== "string" || token.length > 12000) throw deny();
  const parts = token.split("."); if (parts.length !== 3) throw deny();
  let header, claims;
  try { header = JSON.parse(new TextDecoder().decode(decode64(parts[0]))); claims = JSON.parse(new TextDecoder().decode(decode64(parts[1]))); }
  catch { throw deny(); }
  if (header.alg !== "RS256" || typeof header.kid !== "string" || header.kid.length > 200) throw deny();
  let cached = keyCaches.get(fetcher);
  const reused = cached && cached.until > now;
  if (!reused) cached = await googleKeys(fetcher, now);
  let jwk = cached.keys.find(key => key.kid === header.kid && key.kty === "RSA" && (!key.alg || key.alg === "RS256") && (!key.use || key.use === "sig"));
  if (!jwk && reused) {
    cached = await googleKeys(fetcher, now);
    jwk = cached.keys.find(key => key.kid === header.kid && key.kty === "RSA" && (!key.alg || key.alg === "RS256") && (!key.use || key.use === "sig"));
  }
  if (!jwk) throw deny();
  try {
    const key = await crypto.subtle.importKey("jwk", jwk, {name: "RSASSA-PKCS1-v1_5", hash: "SHA-256"}, false, ["verify"]);
    if (!await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, decode64(parts[2]), encoder.encode(parts[0] + "." + parts[1]))) throw deny();
  } catch { throw deny(); }
  if (claims.aud !== audience || !["accounts.google.com", "https://accounts.google.com"].includes(claims.iss) ||
      !Number.isInteger(claims.exp) || claims.exp <= now || !Number.isInteger(claims.iat) || claims.iat > now + 30 ||
      (claims.nbf != null && (!Number.isInteger(claims.nbf) || claims.nbf > now + 30)) ||
      !same(claims.nonce, nonce) || typeof claims.sub !== "string" || !/^[0-9]{5,255}$/.test(claims.sub) ||
      claims.email_verified !== true || typeof claims.email !== "string" || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(claims.email)) throw deny();
  return {sub: claims.sub, email: claims.email.toLowerCase(), name: String(claims.name || claims.email).slice(0, 150), hd: claims.hd || ""};
}
export async function limitedJson(request, limit = 24000) {
  if (!(request.headers.get("Content-Type") || "").startsWith("application/json")) throw new ApiError(415, "JSON_REQUIRED", "Formato de solicitud incorrecto.");
  if (Number(request.headers.get("Content-Length") || 0) > limit) throw new ApiError(413, "TOO_LARGE", "Solicitud demasiado grande.");
  const reader = request.body?.getReader(); if (!reader) throw new ApiError(400, "INVALID_REQUEST", "Solicitud vacía.");
  let size = 0; const parts = [];
  while (true) { const {done, value} = await reader.read(); if (done) break; size += value.byteLength; if (size > limit) { await reader.cancel(); throw new ApiError(413, "TOO_LARGE", "Solicitud demasiado grande."); } parts.push(value); }
  try { const bytes = new Uint8Array(size); let at = 0; for (const part of parts) { bytes.set(part, at); at += part.length; } const result = JSON.parse(new TextDecoder().decode(bytes)); if (!result || Array.isArray(result) || typeof result !== "object") throw new Error(); return result; }
  catch { throw new ApiError(400, "INVALID_REQUEST", "Solicitud incorrecta."); }
}
