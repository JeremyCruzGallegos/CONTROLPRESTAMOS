import {test} from "node:test";
import assert from "node:assert/strict";
import {serviceCredentials} from "./helpers.mjs";
import {FakeSheets} from "./fake-sheets.mjs";
import {SheetsRegistry, registryChunks, newRegistry} from "../src/registry.mjs";

function fixture() {
  const env = {SPREADSHEET_ID: "fixture-registry-spreadsheet-123", SHEETS_SERVICE_ACCOUNT_JSON: serviceCredentials};
  const sheets = new FakeSheets(env.SPREADSHEET_ID); const fetcher = sheets.fetch.bind(sheets);
  return {env, sheets, fetcher, registry: new SheetsRegistry(env, fetcher)};
}
test("initializes only AA:AB in NUEVO and keeps the financial cells and the two-sheet format", async () => {
  const f = fixture(); const original = new Map(f.sheets.cells);
  const result = await f.registry.read(); assert.equal(result.state.bookId, f.env.SPREADSHEET_ID);
  assert.equal(f.sheets.columns, 28); assert.equal(f.sheets.rows, 100);
  for (const [cell, value] of original) assert.equal(f.sheets.cells.get(cell), value);
  assert.equal(f.sheets.cells.get("0:26"), "CP_CONSULTAS_V1");
  assert.ok(f.sheets.scopes.includes("https://www.googleapis.com/auth/spreadsheets"));
});
test("does not overwrite user data or formulas in the reserved area", async () => {
  for (const occupied of ["customer-data", '=IF(TRUE,"","")']) {
    const f = fixture(); f.sheets.columns = 28; f.sheets.cells.set("4:27", occupied);
    await assert.rejects(() => f.registry.read(), error => error.code === "REGISTRY_AREA_OCCUPIED");
    assert.equal(f.sheets.writes.length, 0); assert.equal(f.sheets.cells.get("4:27"), occupied);
  }
});
test("concurrent initializers create a single registry and extend the grid only once", async () => {
  const f = fixture(); const another = new SheetsRegistry(f.env, f.fetcher);
  const [a, b] = await Promise.all([f.registry.read(), another.read()]);
  assert.equal(a.state.bookId, b.state.bookId); assert.equal(f.sheets.columns, 28);
  assert.equal(f.sheets.writes.length, 1);
});
test("concurrent mutations retry the rejected revision without losing either write", async () => {
  const f = fixture(); await f.registry.read(); const another = new SheetsRegistry(f.env, f.fetcher); await another.read();
  const add = (registry, sub) => registry.transact(state => {
    state.accounts[sub] = {sub, email: sub + "@example.invalid", display_name: "Example", request_code: sub, blocked: false, created_at: 10};
  });
  await Promise.all([add(f.registry, "100000000001"), add(another, "200000000002")]);
  assert.equal(Object.keys((await f.registry.read()).state.accounts).length, 2);
  assert.equal(f.sheets.writes.length, 3);
});
test("only one simultaneous redemption can consume a handoff code", async () => {
  const f = fixture(); await f.registry.transact(value => {
    value.accounts["100000000001"] = {sub: "100000000001", email: "a@example.invalid", display_name: "A", request_code: "A", blocked: false, created_at: 1};
    value.handoffs["x".repeat(43)] = {sub: "100000000001", challenge: "test", expires_at: 100};
  });
  const other = new SheetsRegistry(f.env, f.fetcher);
  const consume = registry => registry.transact(value => {
    if (!value.handoffs["x".repeat(43)]) throw new Error("Already consumed");
    delete value.handoffs["x".repeat(43)]; return true;
  });
  const results = await Promise.allSettled([consume(f.registry), consume(other)]);
  assert.equal(results.filter(item => item.status === "fulfilled").length, 1);
});
test("reads cause no Sheets writes when state has not changed", async () => {
  const f = fixture(); await f.registry.read(); const writes = f.sheets.writes.length;
  await f.registry.transact(state => state.bookId); await f.registry.transact(state => state.adminSub);
  assert.equal(f.sheets.writes.length, writes);
});
test("payload chunking preserves Unicode and the entire registry can be loaded again", async () => {
  const f = fixture(); const name = "🙂".repeat(18000);
  await f.registry.transact(state => { state.accounts["100000000001"] = {sub: "100000000001", email: "a@example.invalid", display_name: name, request_code: "A", blocked: false, created_at: 1}; });
  assert.equal((await f.registry.read()).state.accounts["100000000001"].display_name, name);
  const chunks = registryChunks({...newRegistry(f.env.SPREADSHEET_ID), example: name});
  assert.ok(chunks.length > 1); assert.ok(chunks.every(part => !/[\uD800-\uDBFF]$/.test(part)));
});
test("a corrupt registry is rejected instead of silently removing permissions", async () => {
  const f = fixture(); await f.registry.read(); f.sheets.cells.set("2:26", "broken json");
  const writes = f.sheets.writes.length; await assert.rejects(() => f.registry.read(), error => error.code === "REGISTRY_INVALID");
  assert.equal(f.sheets.writes.length, writes);
});
test("verified customer registration is projected visibly into NUEVO without changing financial cells",async()=>{
  const f=fixture();const original=new Map(f.sheets.cells);await f.registry.read();
  const sub='123456789012';await f.registry.transact(state=>{state.accounts[sub]={sub,email:'cliente@gmail.com',display_name:'Cliente',request_code:'REQ',created_at:1793102400,blocked:false,registeredCustomer:true};});
  assert.equal(f.sheets.cells.get('0:29'),'CORREO REGISTRADO');assert.equal(f.sheets.cells.get('1:29'),'cliente@gmail.com');assert.equal(f.sheets.cells.get('1:30'),'Cliente');
  for(const [cell,value]of original)assert.equal(f.sheets.cells.get(cell),value);
  const writes=f.sheets.writes.length;await f.registry.transact(state=>state.accounts[sub].email);assert.equal(f.sheets.writes.length,writes);
  await f.registry.transact(state=>{state.accounts[sub].customerCode='CP-20261027-C-1234ABCD';});assert.equal(f.sheets.cells.get('1:31'),'CP-20261027-C-1234ABCD');
});
test("registration projection refuses to overwrite unrelated cells",async()=>{
  const f=fixture();await f.registry.read();f.sheets.columns=33;f.sheets.cells.set('0:29','Owner data');
  const sub='123456789012';await assert.rejects(()=>f.registry.transact(state=>{state.accounts[sub]={sub,email:'cliente@gmail.com',display_name:'Cliente',request_code:'REQ',created_at:1793102400,blocked:false,registeredCustomer:true};}),error=>error.code==='REGISTRATION_AREA_OCCUPIED');
  assert.equal(f.sheets.cells.get('0:29'),'Owner data');assert.equal((await f.registry.read()).state.accounts[sub],undefined);
});
