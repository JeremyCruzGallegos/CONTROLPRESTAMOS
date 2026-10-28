import {ApiError, base64url, nowSeconds} from "./security.mjs";
const encoder = new TextEncoder();
const epoch = Date.UTC(1899, 11, 30);
const start = "2026-01-01";
const DAY = 86400000;
export const HEADERS = ["ID", "CLIENTE", "PRESTAMO", "TASA MENSUAL", "MESES", "GARANTIA", "DESEMBOLSO", "APORTE", "MORA DIARIA", "TOTAL PACTADO", "CUOTA DIARIA", "PRIMERA CUOTA", "PRIMER PAGO", "ULTIMO PAGO", "ESTADO", "REVISION"];
const invalid = () => new ApiError(503, "BOOK_NEEDS_REVIEW", "El prestamista debe revisar los datos del préstamo en la hoja.");
export function dateValue(value) {
  let result;
  if (typeof value === "number" && Number.isInteger(value)) result = new Date(epoch + value * DAY).toISOString().slice(0, 10);
  else if (typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value)) result = value;
  else throw invalid();
  if (new Date(result + "T00:00:00Z").toISOString().slice(0, 10) !== result) throw invalid();
  return result;
}
export const daysBetween = (a, b) => Math.round((Date.parse(b + "T00:00:00Z") - Date.parse(a + "T00:00:00Z")) / DAY);
export const plusDays = (day, count) => new Date(Date.parse(day + "T00:00:00Z") + count * DAY).toISOString().slice(0, 10);
export function money(value) {
  if ((typeof value !== "number" && typeof value !== "string") || value === "" || !Number.isFinite(Number(value))) throw invalid();
  const amount = Number(value); const cents = Math.round((amount + Number.EPSILON) * 100);
  if (amount < 0 || !Number.isSafeInteger(cents) || cents > 100000000000) throw invalid();
  return cents;
}
export const todayLima = () => new Intl.DateTimeFormat("en-CA", {timeZone: "America/Lima", year: "numeric", month: "2-digit", day: "2-digit"}).format(new Date());
export function columnName(number) { let text = ""; while (number > 0) { number--; text = String.fromCharCode(65 + number % 26) + text; number = Math.floor(number / 26); } return text; }
export function parseLoan(row, asOf) {
  const id = String(row[0] || ""); if (!/^20\d{2}-\d{3,}$/.test(id)) throw invalid();
  if (row[14] === "ELIMINADO") return null;
  if (row[14] !== "ACTIVO" || !String(row[1] || "").trim()) throw invalid();
  const disbursedOn = dateValue(row[6]), firstDue = dateValue(row[12]), lastDue = dateValue(row[13]);
  const months = Number(row[4]); const principalCents = money(row[2]), agreedCents = money(row[9]), dailyLateCents = money(row[8]);
  const rate = Number(row[3]);
  if (!Number.isInteger(months) || months < 1 || months > 24 || principalCents <= 0 || !Number.isFinite(rate) || rate < 0 || rate > 1 ||
      dailyLateCents > 1000000 || disbursedOn < start || !id.startsWith(disbursedOn.slice(0, 4)) || firstDue !== plusDays(disbursedOn, 1) || lastDue !== plusDays(firstDue, months * 30 - 1)) throw invalid();
  let conditions = null;
  if (String(row[15] || "").startsWith("{")) { try { conditions = JSON.parse(row[15]); } catch { throw invalid(); } }
  if (!row[15] || conditions && (conditions.dailyLate !== dailyLateCents || !conditions.bases || typeof conditions.revision !== "string")) throw invalid();
  const advances = conditions?.advances || []; if (!Array.isArray(advances) || advances.length > 5000) throw invalid();
  const installments = Array.from({length: months * 30}, (_, index) => {
    const due = plusDays(firstDue, index), col = 16 + daysBetween(start, due) * 4;
    const unpaid = row[col]; if (typeof unpaid !== "boolean") throw invalid();
    const baseCents = money(row[col + 1]); if (baseCents <= 0 || conditions && conditions.bases[due] !== baseCents) throw invalid();
    const actualDate = row[col + 2] == null || row[col + 2] === "" ? null : dateValue(row[col + 2]);
    if (actualDate && (actualDate < due || actualDate > asOf)) throw invalid();
    const recordedLate = row[col + 3] == null || row[col + 3] === "" ? 0 : money(row[col + 3]);
    const paid = due <= asOf && !unpaid;
    return {number: index + 1, due, baseCents, recordedLateCents: recordedLate, lateCents: paid ? recordedLate : 0,
      status: due > asOf ? "FUTURE" : paid ? (actualDate ? "PAID" : "REVIEW") : due < asOf ? "OVERDUE" : "PENDING",
      paidOn: paid ? actualDate : null, paidCents: paid ? baseCents + recordedLate : 0, paid};
  });
  const byDate = new Map(installments.map(item => [item.due, item]));
  const credits = new Map();
  for (const credit of advances) {
    if (!byDate.has(credit.source) || !byDate.has(credit.target) || credit.target <= credit.source || !Number.isSafeInteger(credit.amount) || credit.amount <= 0 || credit.amount > 10000000000) throw invalid();
    if (byDate.get(credit.source).paid) credits.set(credit.target, (credits.get(credit.target) || 0) + credit.amount);
  }
  for (const item of installments) if (!item.paid && item.due <= asOf) item.lateCents = Math.max(0, Math.max(0, daysBetween(item.due, asOf)) * dailyLateCents - (credits.get(item.due) || 0));
  if (installments.reduce((sum, item) => sum + item.baseCents, 0) !== agreedCents || installments[0].baseCents !== money(row[11]) || installments[1].baseCents !== money(row[10]) ||
      Math.abs(Math.round(principalCents * (1 + rate * months)) - agreedCents) > 1) throw invalid();
  const paidCount = installments.filter(item => item.paid).length;
  return {id, clientName: String(row[1]).slice(0, 200), principalCents, agreedCents, ratePercent: rate * 100, months,
    disbursedOn, firstDue, lastDue, dailyLateCents, paidCount, totalCount: installments.length,
    balanceCents: installments.filter(item => !item.paid).reduce((sum, item) => sum + item.baseCents + item.lateCents, 0),
    collectedCents: installments.reduce((sum, item) => sum + item.paidCents, 0),
    overdueCents: installments.filter(item => item.status === "OVERDUE").reduce((sum, item) => sum + item.baseCents + item.lateCents, 0),
    pendingLateCents: installments.filter(item => !item.paid).reduce((sum, item) => sum + item.lateCents, 0),
    completed: paidCount === installments.length, needsReview: installments.some(item => item.status === "REVIEW"),
    installments: installments.map(({paid, ...item}) => item)};
}
const tokens = new WeakMap();
async function serviceToken(env, fetcher, write = false) {
  const scope = "https://www.googleapis.com/auth/spreadsheets" + (write ? "" : ".readonly");
  const cache = tokens.get(env) || new Map();
  const cached = cache.get(scope); if (cached && cached.until > nowSeconds() + 60) return cached.value;
  let credentials; try { credentials = JSON.parse(env.SHEETS_SERVICE_ACCOUNT_JSON); } catch { throw new ApiError(503, "SETUP_REQUIRED", "El servicio de consultas aún no está configurado."); }
  if (!String(credentials.client_email).endsWith(".iam.gserviceaccount.com") || !String(credentials.private_key).includes("BEGIN PRIVATE KEY")) throw invalid();
  let key;
  try {
    const raw = credentials.private_key.replace(/-----[^-]+-----/g, "").replace(/\s/g, "");
    key = await crypto.subtle.importKey("pkcs8", Uint8Array.from(atob(raw), c => c.charCodeAt(0)), {name: "RSASSA-PKCS1-v1_5", hash: "SHA-256"}, false, ["sign"]);
  } catch {
    throw new ApiError(503, "SERVICE_KEY_INVALID", "La clave privada de la cuenta de servicio no es válida. Revisa SHEETS_SERVICE_ACCOUNT_JSON en Cloudflare.");
  }
  const now = nowSeconds(); const header = base64url(encoder.encode(JSON.stringify({alg: "RS256", typ: "JWT"})));
  const payload = base64url(encoder.encode(JSON.stringify({iss: credentials.client_email, scope, aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600})));
  const signature = base64url(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, encoder.encode(header + "." + payload)));
  const response = await fetcher("https://oauth2.googleapis.com/token", {method: "POST", redirect: "manual", body: new URLSearchParams({grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: header + "." + payload + "." + signature})});
  if (!response.ok) throw new ApiError(503, "SHEETS_UNAVAILABLE", "No se pudo consultar la hoja. El prestamista debe revisar su acceso.");
  const body = await response.json(); if (!body.access_token || !Number.isFinite(body.expires_in)) throw invalid();
  cache.set(scope, {value: body.access_token, until: now + body.expires_in}); tokens.set(env, cache); return body.access_token;
}
export class SheetsFailure extends ApiError {
  constructor(status, details = "") { super(503, "SHEETS_UNAVAILABLE", "No se pudo consultar o actualizar el acceso en la hoja. Reintenta más tarde."); this.googleStatus = status; this.details = details; }
}
export async function sheetsApi(env, suffix, {method = "GET", body = null, write = false} = {}, fetcher = fetch) {
  if (!/^[A-Za-z0-9_-]{20,100}$/.test(env.SPREADSHEET_ID || "") || !/^[/:?]/.test(suffix)) throw invalid();
  if (method !== "GET" && (method !== "POST" || suffix !== ":batchUpdate" || !write)) throw invalid();
  const response = await fetcher("https://sheets.googleapis.com/v4/spreadsheets/" + env.SPREADSHEET_ID + suffix, {
    method, redirect: "manual", headers: {Authorization: "Bearer " + await serviceToken(env, fetcher, write), ...(body ? {"Content-Type": "application/json"} : {})},
    ...(body ? {body: JSON.stringify(body)} : {})});
  if (!response.ok) {
    const failure = await response.json().catch(() => ({}));
    throw new SheetsFailure(response.status, String(failure.error?.message || ""));
  }
  const result = await response.json();
  if (!suffix.startsWith("/values/") && result.spreadsheetId !== env.SPREADSHEET_ID) throw invalid();
  return result;
}
async function values(env, ranges, fetcher) {
  if (!/^[A-Za-z0-9_-]{20,100}$/.test(env.SPREADSHEET_ID || "")) throw invalid();
  const url = new URL("https://sheets.googleapis.com/v4/spreadsheets/" + env.SPREADSHEET_ID + "/values:batchGet");
  for (const range of ranges) url.searchParams.append("ranges", range);
  url.searchParams.set("valueRenderOption", "UNFORMATTED_VALUE"); url.searchParams.set("dateTimeRenderOption", "SERIAL_NUMBER");
  const response = await fetcher(url, {headers: {Authorization: "Bearer " + await serviceToken(env, fetcher)}, redirect: "manual"});
  if (!response.ok) throw new ApiError(503, "SHEETS_UNAVAILABLE", "La hoja no está disponible. Reintenta en unos momentos.");
  const body = await response.json(); if (body.spreadsheetId !== env.SPREADSHEET_ID || body.valueRanges?.length !== ranges.length) throw invalid();
  return body.valueRanges.map(item => item.values || []);
}
export async function readCatalog(env, fetcher = fetch) {
  const [config, rows] = await values(env, ["'NUEVO'!A1:B5", "'PRESTAMOS'!A1:P"], fetcher);
  if (config[0]?.[0] !== "CP_SIMPLE_V2" || dateValue(config[3]?.[1]) !== start) throw invalid();
  const until = dateValue(config[4]?.[1]);
  const header = rows.findIndex(row => HEADERS.every((label, i) => row[i] === label)); if (header < 0 || header > 20 || rows.length > 100000) throw invalid();
  const ids = new Set();
  const loans = rows.slice(header + 1).map((row, index) => ({row, sheetRow: header + index + 2})).filter(item => item.row[0] || item.row[1]).map(item => {
    const id = String(item.row[0] || "");
    if (!/^20\d{2}-\d{3,}$/.test(id) || ids.has(id)) throw invalid(); ids.add(id);
    return {...item, id, deleted: item.row[14] === "ELIMINADO", clientName: String(item.row[1] || "")};
  });
  return {until, loans};
}
export async function readAuthorizedLoans(env, loanIds, fetcher = fetch) {
  if (!loanIds.length) return [];
  const catalog = await readCatalog(env, fetcher); const allowed = new Set(loanIds);
  const matches = catalog.loans.filter(item => allowed.has(item.id) && !item.deleted);
  if (!matches.length) return [];
  const asOf = todayLima(); const endColumn = columnName(16 + (daysBetween(start, catalog.until) + 1) * 4);
  if (daysBetween(start, catalog.until) > 5000) throw invalid();
  const rows = await values(env, matches.map(item => `'PRESTAMOS'!A${item.sheetRow}:${endColumn}${item.sheetRow}`), fetcher);
  return rows.map((row, index) => {
    if (String(row[0]?.[0]) !== matches[index].id || !allowed.has(String(row[0]?.[0]))) throw new ApiError(503, "BOOK_CHANGED", "La hoja cambió durante la consulta. Actualiza nuevamente.");
    return parseLoan(row[0], asOf);
  }).filter(Boolean).sort((a, b) => b.disbursedOn.localeCompare(a.disbursedOn) || b.id.localeCompare(a.id));
}
