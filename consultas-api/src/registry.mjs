import {ApiError, decode64, hash, randomToken} from "./security.mjs";
import {sheetsApi, SheetsFailure} from "./sheets.mjs";

export const REGISTRY_MAGIC = "CP_CONSULTAS_V1";
export const REGISTRY_ROWS = 32;
export const REGISTRY_COLUMN = 26; // AA; the financial app writes NUEVO only in A:K.
export const REGISTRY_RANGE = "'NUEVO'!AA1:AB32";
const CHUNK = 30000;
const fields = ["accounts", "sessions", "flows", "handoffs", "grants"];
const unavailable = (code, message) => new ApiError(503, code, message);
export function newRegistry(bookId) {
  return {version: 1, bookId, adminSub: null, accounts: {}, sessions: {}, flows: {}, handoffs: {}, grants: {}};
}
export function validateRegistry(state, bookId) {
  if (!state || state.version !== 1 || state.bookId !== bookId || fields.some(key => !state[key] || Array.isArray(state[key]) || typeof state[key] !== "object") ||
      state.adminSub != null && (typeof state.adminSub !== "string" || !/^\d{5,255}$/.test(state.adminSub))) throw unavailable("REGISTRY_INVALID", "El prestamista debe revisar el registro de accesos de la hoja.");
  for (const key of fields) if (Object.keys(state[key]).length > 5000) throw unavailable("REGISTRY_FULL", "El registro de accesos necesita mantenimiento.");
  if (state.additionalAdmins != null && (typeof state.additionalAdmins !== "object" || Array.isArray(state.additionalAdmins) ||
      Object.keys(state.additionalAdmins).length > 100 || Object.entries(state.additionalAdmins).some(([email, sub]) =>
        !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || typeof sub !== "string" || !state.accounts[sub])))
    throw unavailable("REGISTRY_INVALID", "El prestamista debe revisar los administradores de consulta.");
  if (state.customers != null && (typeof state.customers !== "object" || Array.isArray(state.customers) || Object.keys(state.customers).length > 5000))
    throw unavailable("REGISTRY_INVALID", "El prestamista debe revisar los códigos de cliente.");
  const assigned = new Set(), linked = new Set();
  for (const [code, customer] of Object.entries(state.customers || {})) {
    if (!/^CP-(?:[0-9A-F]{20}|[0-9]{8}-[A-Z]{1,4}-[0-9A-F]{8})$/.test(code) || customer.code !== code || typeof customer.name !== "string" || !customer.name.trim() || customer.name.length > 200 ||
      typeof customer.enabled !== "boolean" || !Array.isArray(customer.loanIds) || customer.loanIds.length > 100 || customer.linkedSub != null && !state.accounts[customer.linkedSub])
      throw unavailable("REGISTRY_INVALID", "El prestamista debe revisar los clientes.");
    if (customer.linkedSub) { if(linked.has(customer.linkedSub)) throw unavailable("REGISTRY_INVALID","Una cuenta tiene varios clientes."); linked.add(customer.linkedSub); }
    for (const id of customer.loanIds) { if(!/^20\d{2}-\d{3,}$/.test(id) || assigned.has(id)) throw unavailable("REGISTRY_INVALID","Un préstamo tiene varios clientes."); assigned.add(id); }
  }
  if (state.paymentInstructions != null && (typeof state.paymentInstructions !== "string" || state.paymentInstructions.length > 4000)) throw unavailable("REGISTRY_INVALID","Revisa las instrucciones de pago.");
  const accounts = state.accounts;
  for (const [sub, account] of Object.entries(accounts)) {
    if (!/^\d{5,255}$/.test(sub) || account.sub !== sub || typeof account.email !== "string" || typeof account.display_name !== "string" ||
        typeof account.request_code !== "string" || typeof account.blocked !== "boolean" || !Number.isInteger(account.created_at)) throw unavailable("REGISTRY_INVALID", "El prestamista debe revisar las cuentas de consulta.");
  }
  for (const [id, grant] of Object.entries(state.grants)) if (!/^20\d{2}-\d{3,}$/.test(id) || !accounts[grant.sub]) throw unavailable("REGISTRY_INVALID", "El prestamista debe revisar las vinculaciones de préstamos.");
  for (const kind of ["sessions", "handoffs"]) for (const [id, value] of Object.entries(state[kind])) if (!/^[A-Za-z0-9_-]{43}$/.test(id) || !accounts[value.sub] || !Number.isInteger(value.expires_at)) throw unavailable("REGISTRY_INVALID", "El prestamista debe revisar las sesiones de consulta.");
  for (const [id, value] of Object.entries(state.flows)) if (!/^[A-Za-z0-9_-]{43}$/.test(id) || !Number.isInteger(value.expires_at)) throw unavailable("REGISTRY_INVALID", "El prestamista debe revisar las conexiones de consulta.");
  return state;
}
export function registryChunks(state) {
  const value = JSON.stringify(state); const chunks = [];
  for (let at = 0; at < value.length;) {
    let end = Math.min(at + CHUNK, value.length);
    const char = value.charCodeAt(end - 1); if (char >= 0xD800 && char <= 0xDBFF) end--;
    chunks.push(value.slice(at, end)); at = end;
  }
  if (chunks.length > REGISTRY_ROWS - 2) throw unavailable("REGISTRY_FULL", "El registro de consultas alcanzó su capacidad. Contacta con el prestamista.");
  return chunks;
}
export function purgeExpired(state, now) {
  for (const kind of ["sessions", "flows", "handoffs"]) for (const [id, value] of Object.entries(state[kind])) if (value.expires_at <= now) delete state[kind][id];
}
function cellsRequest(sheetId, revision, chunks, bookId) {
  const string = value => ({userEnteredValue: {stringValue: String(value)}});
  return {updateCells: {start: {sheetId, rowIndex: 0, columnIndex: REGISTRY_COLUMN}, fields: "userEnteredValue", rows: [
    {values: [string(REGISTRY_MAGIC), string(bookId)]}, {values: [string(revision), {userEnteredValue: {numberValue: chunks.length}}]},
    ...chunks.map(chunk => ({values: [string(chunk)]}))
  ]}};
}
async function guardId(revision) { return "cp_consultas_cas_" + Array.from(decode64(await hash(revision))).map(byte => byte.toString(16).padStart(2, "0")).join(""); }
function guard(sheetId, id) { return {addNamedRange: {namedRange: {namedRangeId: id, name: id,
  range: {sheetId, startRowIndex: 1, endRowIndex: 2, startColumnIndex: REGISTRY_COLUMN, endColumnIndex: REGISTRY_COLUMN + 1}}}}; }
function conflict(error) { return error instanceof SheetsFailure && error.googleStatus === 400 && /already exists|already been used/i.test(error.details) && /named range|namedrange/i.test(error.details); }

/** Google Sheets is the only persistent store. Duplicate revision guard IDs reject
 * the entire batch atomically, so competing login or permission updates cannot
 * overwrite one another. Old guards must be retained, like financial revision guards.
 */
export class SheetsRegistry {
  constructor(env, fetcher = fetch) { this.env = env; this.fetcher = fetcher; this.sheetId = null; }
  api(suffix, options = {}) { return sheetsApi(this.env, suffix, options, this.fetcher); }
  async metadata() {
    const meta = await this.api("?fields=" + encodeURIComponent("spreadsheetId,properties(timeZone),sheets(properties(sheetId,title,gridProperties))"));
    if (meta.properties?.timeZone !== "America/Lima" || !Array.isArray(meta.sheets) || meta.sheets.length < 2 || meta.sheets.length > 3 || meta.sheets.some(item => !["NUEVO","PRESTAMOS","CLIENTES"].includes(item.properties.title)) || !meta.sheets.some(item => item.properties.title === "PRESTAMOS")) throw unavailable("BOOK_CONFIGURATION_CHANGED", "La hoja no tiene el formato esperado para el prestamista.");
    const sheet = meta.sheets.find(item => item.properties.title === "NUEVO")?.properties;
    if (!sheet) throw unavailable("BOOK_CONFIGURATION_CHANGED", "Falta la pestaña de configuración del libro.");
    this.sheetId = sheet.sheetId; return sheet;
  }
  async readCells(sheet) {
    const columns = sheet?.gridProperties.columnCount ?? 28, rows = Math.min(sheet?.gridProperties.rowCount ?? REGISTRY_ROWS, REGISTRY_ROWS);
    if (columns <= REGISTRY_COLUMN) return [];
    const last = columns > 27 ? "AB" : "AA";
    const suffix = "/values/" + encodeURIComponent(`'NUEVO'!AA1:${last}${rows}`) + "?valueRenderOption=FORMULA";
    const response = await this.api(suffix); return response.values || [];
  }
  decode(cells) {
    if (cells[0]?.[0] !== REGISTRY_MAGIC || cells[0]?.[1] !== this.env.SPREADSHEET_ID) throw unavailable("BOOK_CONFIGURATION_CHANGED", "El registro de acceso pertenece a otro libro o cambió de formato.");
    const revision = cells[1]?.[0], count = Number(cells[1]?.[1]);
    if (typeof revision !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(revision) || !Number.isInteger(count) || count < 1 || count > REGISTRY_ROWS - 2) throw unavailable("REGISTRY_INVALID", "El prestamista debe revisar el registro de accesos.");
    let state; try { state = JSON.parse(Array.from({length: count}, (_, i) => cells[i + 2]?.[0] ?? "").join("")); } catch { throw unavailable("REGISTRY_INVALID", "El registro de accesos está incompleto."); }
    return {revision, state: validateRegistry(state, this.env.SPREADSHEET_ID)};
  }
  async initialize() {
    const sheet = await this.metadata(); const existing = await this.readCells(sheet);
    if (existing[0]?.[0] === REGISTRY_MAGIC) return this.decode(existing);
    if (existing.some(row => row.some(cell => cell !== "" && cell != null))) throw unavailable("REGISTRY_AREA_OCCUPIED", "El espacio reservado de consultas contiene información. El prestamista debe revisarlo antes de habilitar los accesos.");
    const config = await this.api("/values/" + encodeURIComponent("'NUEVO'!A1:B5") + "?valueRenderOption=UNFORMATTED_VALUE");
    if (config.values?.[0]?.[0] !== "CP_SIMPLE_V2") throw unavailable("BOOK_CONFIGURATION_CHANGED", "La hoja no tiene el formato esperado.");
    const state = newRegistry(this.env.SPREADSHEET_ID), revision = randomToken(), requests = [];
    if (sheet.gridProperties.columnCount < 28) requests.push({appendDimension: {sheetId: this.sheetId, dimension: "COLUMNS", length: 28 - sheet.gridProperties.columnCount}});
    if (sheet.gridProperties.rowCount < REGISTRY_ROWS) requests.push({appendDimension: {sheetId: this.sheetId, dimension: "ROWS", length: REGISTRY_ROWS - sheet.gridProperties.rowCount}});
    requests.push(guard(this.sheetId, "cp_consultas_init_v1"), cellsRequest(this.sheetId, revision, registryChunks(state), this.env.SPREADSHEET_ID));
    try { await this.api(":batchUpdate", {method: "POST", write: true, body: {requests}}); }
    catch (error) { if (!conflict(error)) throw error; this.sheetId = null; return this.read(); }
    return {revision, state};
  }
  async read() {
    if (this.sheetId == null) return this.initialize();
    return this.decode(await this.readCells());
  }
  async accountProjection(before,state) {
    const rows = Object.values(state.accounts).filter(account => account.registeredCustomer === true).sort((a,b)=>a.created_at-b.created_at || a.sub.localeCompare(b.sub));
    const oldRows = Object.values(before.accounts).filter(account => account.registeredCustomer === true).sort((a,b)=>a.created_at-b.created_at || a.sub.localeCompare(b.sub));
    if (state.accountProjectionVersion === 1 && rows.length === oldRows.length && rows.every((account,i)=>["email","display_name","customerCode","created_at"].every(key=>account[key]===oldRows[i][key])) || !rows.length && !oldRows.length) return [];
    const props=await this.metadata();const columns=props.gridProperties.columnCount, count=Math.max(rows.length,oldRows.length)+1;
    const headers=["CORREO REGISTRADO","NOMBRE GOOGLE","CÓDIGO CLIENTE","FECHA DE REGISTRO"];
    if (state.accountProjectionVersion !== 1 && columns>=30) {
      const last=["AD","AE","AF","AG"][Math.min(columns-30,3)];
      const data=await this.api("/values/"+encodeURIComponent(`'NUEVO'!AD1:${last}${Math.min(count,props.gridProperties.rowCount)}`)+"?valueRenderOption=FORMULA");
      const existing=data.values || [];
      if(existing.some(row=>row.some(value=>value!=="" && value!=null)) && !headers.every((header,i)=>existing[0]?.[i]===header))
        throw unavailable("REGISTRATION_AREA_OCCUPIED","El espacio de correos registrados en NUEVO contiene otros datos. Revisa AD:AG.");
    }
    const requests=[];
    if(columns<33)requests.push({appendDimension:{sheetId:this.sheetId,dimension:"COLUMNS",length:33-columns}});
    if(props.gridProperties.rowCount<count)requests.push({appendDimension:{sheetId:this.sheetId,dimension:"ROWS",length:count-props.gridProperties.rowCount}});
    const date=new Intl.DateTimeFormat("es-PE",{timeZone:"America/Lima",dateStyle:"short",timeStyle:"short"});
    const values=[headers,...rows.map(account=>[account.email,account.display_name,account.customerCode || "",date.format(new Date(account.created_at*1000))])];
    while(values.length<count)values.push(["","","",""]);
    requests.push({updateCells:{start:{sheetId:this.sheetId,rowIndex:0,columnIndex:29},fields:"userEnteredValue",
      rows:values.map(row=>({values:row.map(value=>({userEnteredValue:{stringValue:String(value)}}))}))}});
    state.accountProjectionVersion=1;
    return requests;
  }
  async transact(mutate) {
    for (let attempt = 0; attempt < 4; attempt++) {
      const before = await this.read(), state = structuredClone(before.state);
      const result = await mutate(state);
      const projection=await this.accountProjection(before.state,state);
      validateRegistry(state, this.env.SPREADSHEET_ID);
      if (JSON.stringify(state) === JSON.stringify(before.state)) return result;
      const chunks = registryChunks(state), revision = randomToken();
      try {
        await this.api(":batchUpdate", {method: "POST", write: true, body: {requests: [
          guard(this.sheetId, await guardId(before.revision)), cellsRequest(this.sheetId, revision, chunks, this.env.SPREADSHEET_ID), ...projection
        ]}});
        return result;
      } catch (error) {
        if (!conflict(error)) throw error;
      }
    }
    throw new ApiError(409, "ACCESS_CHANGED", "Los accesos cambiaron durante la operación. Reintenta nuevamente.");
  }
}
