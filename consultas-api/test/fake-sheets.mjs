/** Models Sheets' atomic batches and duplicate namedRangeId rejection. No network. */
export class FakeSheets {
  constructor(id) {
    this.id = id; this.sheetId = 1348443244; this.columns = 11; this.rows = 100;
    this.directoryId=null;this.directoryRows=100;this.directoryCells=new Map();
    this.cells = new Map(); this.guards = new Set(); this.writes = []; this.scopes = []; this.tokens = new Map();
    [["CP_SIMPLE_V2", "config"], ["CAPITAL INICIAL", 0], ["REVISION", "financial-revision"], ["CALENDARIO DESDE", "2026-01-01"], ["CALENDARIO HASTA", "2026-12-31"]]
      .forEach((row, r) => row.forEach((value, c) => this.cells.set(`${r}:${c}`, value)));
    this.cells.set("23:0", "financial-journal-preserved"); this.cells.set("23:10", "financial-reference-preserved");
  }
  json(body, status = 200) { return new Response(JSON.stringify(body), {status}); }
  async fetch(url, options = {}) {
    const text = String(url);
    if (text === "https://oauth2.googleapis.com/token" && options.body?.get?.("grant_type") === "urn:ietf:params:oauth:grant-type:jwt-bearer") {
      const payload = JSON.parse(Buffer.from(options.body.get("assertion").split('.')[1], 'base64url').toString());
      this.scopes.push(payload.scope); const token = "fixture-service-token-" + this.tokens.size; this.tokens.set(token, payload.scope);
      return this.json({access_token: token, expires_in: 3600});
    }
    const parsed = new URL(url); if (parsed.hostname !== "sheets.googleapis.com") return null;
    const scope = this.tokens.get((options.headers?.Authorization || "").replace("Bearer ", ""));
    if (!scope) return this.json({error: {message: "Not authorized"}}, 401);
    if (parsed.pathname.endsWith(":batchUpdate")) {
      if (scope !== "https://www.googleapis.com/auth/spreadsheets") return this.json({error: {message: "Read-only scope"}}, 403);
      const requests = JSON.parse(options.body).requests;
      const cells = new Map(this.cells), guards = new Set(this.guards), directoryCells=new Map(this.directoryCells); let columns = this.columns, rows = this.rows, directoryId=this.directoryId, directoryRows=this.directoryRows;
      for (const request of requests) {
        if(request.addSheet){
          if(directoryId!=null)return this.json({error:{message:"A sheet with the name CLIENTES already exists."}},400);
          const props=request.addSheet.properties;if(props.title!=="CLIENTES")throw Error("Unexpected sheet");
          directoryId=props.sheetId;directoryRows=props.gridProperties.rowCount;
        } else if(request.repeatCell || request.updateDimensionProperties){
          // Presentation requests do not change values.
        } else if (request.addNamedRange) {
          const range = request.addNamedRange.namedRange;
          if (guards.has(range.namedRangeId)) return this.json({error: {message: `Named range ID ${range.namedRangeId} already exists.`}}, 400);
          guards.add(range.namedRangeId);
        } else if (request.appendDimension) {
          const item = request.appendDimension; if(item.sheetId===directoryId && item.dimension==="ROWS"){directoryRows+=item.length;continue;} if (item.sheetId !== this.sheetId) throw new Error("Attempted to resize a financial sheet");
          if (item.dimension === "COLUMNS") columns += item.length; else if (item.dimension === "ROWS") rows += item.length; else throw new Error("Invalid dimension");
        } else if (request.updateCells) {
          const update = request.updateCells;
          if(update.start.sheetId===directoryId){
            update.rows.forEach((row,offset)=>row.values?.forEach((cell,c)=>directoryCells.set(`${update.start.rowIndex+offset}:${update.start.columnIndex+c}`,cell.userEnteredValue.stringValue)));
            continue;
          }
          if (update.start.sheetId !== this.sheetId || ![26,29].includes(update.start.columnIndex) || update.fields !== "userEnteredValue") throw new Error("Attempted to change financial cells");
          update.rows.forEach((row, offset) => row.values?.forEach((cell, c) => {
            if (c > (update.start.columnIndex===29?3:1)) throw new Error("Outside the registry columns");
            cells.set(`${update.start.rowIndex + offset}:${update.start.columnIndex + c}`, cell.userEnteredValue.stringValue ?? cell.userEnteredValue.numberValue);
          }));
        } else throw new Error("Unexpected mutation request");
      }
      this.directoryId=directoryId;this.directoryRows=directoryRows;this.directoryCells=directoryCells;this.cells = cells; this.guards = guards; this.columns = columns; this.rows = rows; this.writes.push(requests);
      return this.json({spreadsheetId: this.id, replies: requests.map(() => ({}))});
    }
    if (parsed.pathname.includes("/values/")) {
      const range = decodeURIComponent(parsed.pathname.split("/values/")[1]);
      const isDirectory=range.startsWith("'CLIENTES'!");
      const match = range.match(/^'(?:NUEVO|CLIENTES)'!([A-Z]+)(\d+):([A-Z]+)(\d+)$/); if (!match) throw new Error("Unbounded test range " + range);
      const col = value => [...value].reduce((n, c) => n * 26 + c.charCodeAt(0) - 64, 0) - 1;
      const from = col(match[1]), to = col(match[3]), top = Number(match[2]) - 1, end = Number(match[4]);
      if (to >= (isDirectory?8:this.columns) || end > (isDirectory?this.directoryRows:this.rows)) return this.json({error: {message: "Range exceeds grid limits"}}, 400);
      const values = Array.from({length: end - top}, (_, r) => {
        const row = Array.from({length: to - from + 1}, (_, c) => (isDirectory?this.directoryCells:this.cells).get(`${top + r}:${from + c}`) ?? "");
        while (row.length && row.at(-1) === "") row.pop(); return row;
      });
      while (values.length && !values.at(-1).length) values.pop();
      return this.json({range, majorDimension: "ROWS", values});
    }
    return this.json({spreadsheetId: this.id, properties: {timeZone: "America/Lima"}, sheets: [
      {properties: {sheetId: this.sheetId, title: "NUEVO", gridProperties: {columnCount: this.columns, rowCount: this.rows}}},
      {properties: {sheetId: 16026001, title: "PRESTAMOS", gridProperties: {columnCount: 1476, rowCount: 106}}},
      ...(this.directoryId==null?[]:[{properties:{sheetId:this.directoryId,title:"CLIENTES",gridProperties:{columnCount:8,rowCount:this.directoryRows}}}])
    ]});
  }
}
