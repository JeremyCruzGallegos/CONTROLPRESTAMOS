import {test} from "node:test";
import assert from "node:assert/strict";
import {parseLoan, plusDays, daysBetween, money, readAuthorizedLoans, HEADERS} from "../src/sheets.mjs";
import {signingKey} from "./helpers.mjs";

const asOf = "2026-10-27";
function loanRow({id = "2026-001", client = "Ana", first = "2026-10-01", months = 1} = {}) {
  const days = months * 30, last = plusDays(first, days - 1); const row = [];
  const fixed = [id, client, 300, .02, months, "Garantía privada", plusDays(first, -1), 0, 1, 300 * (1 + .02 * months), 10.2, 10.2, first, last, "ACTIVO", "revision-1"];
  fixed.forEach((value, i) => row[i] = value);
  for (let i = 0; i < days; i++) { const col = 16 + daysBetween("2026-01-01", plusDays(first, i)) * 4; row[col] = true; row[col + 1] = 10.2; row[col + 2] = ""; row[col + 3] = 0; }
  return row;
}
function setDay(row, due, {unpaid, paidOn = "", late = 0, base = 10.2}) { const col = 16 + daysBetween("2026-01-01", due) * 4; row[col] = unpaid; row[col + 1] = base; row[col + 2] = paidOn; row[col + 3] = late; }
test("totals reproduce flat daily late fees, future installments and actual receipts", () => {
  const row = loanRow(); setDay(row, "2026-10-01", {unpaid: false, paidOn: "2026-10-03", late: 2});
  const loan = parseLoan(row, asOf);
  assert.equal(loan.principalCents, 30000); assert.equal(loan.agreedCents, 30600); assert.equal(loan.collectedCents, 1220); assert.equal(loan.paidCount, 1);
  const late = loan.installments.filter(item => item.status === "OVERDUE").reduce((sum, item) => sum + item.lateCents, 0);
  assert.equal(late, 32500); assert.equal(loan.balanceCents, 29580 + late);
  assert.equal(loan.installments[26].status, "PENDING"); assert.equal(loan.installments[26].lateCents, 0);
  assert.equal(loan.installments[29].status, "FUTURE"); assert.equal(loan.installments[29].lateCents, 0);
});
test("a checked payment without a date is disclosed as needing review instead of inventing a date", () => {
  const row = loanRow(); setDay(row, "2026-10-01", {unpaid: false});
  const loan = parseLoan(row, asOf); assert.equal(loan.needsReview, true); assert.equal(loan.paidCount, 1);
  assert.equal(loan.installments[0].status, "REVIEW"); assert.equal(loan.installments[0].paidOn, null);
});
test("allocated late-fee advances subtract only when their source installment is paid", () => {
  const row = loanRow(); const bases = Object.fromEntries(Array.from({length: 30}, (_, i) => [plusDays("2026-10-01", i), 1020]));
  row[15] = JSON.stringify({dailyLate: 100, revision: "revision-2", bases, advances: [{source: "2026-10-01", target: "2026-10-02", amount: 200}]});
  setDay(row, "2026-10-01", {unpaid: false, paidOn: "2026-10-03"});
  assert.equal(parseLoan(row, asOf).installments[1].lateCents, 2300);
  setDay(row, "2026-10-01", {unpaid: true});
  assert.equal(parseLoan(row, asOf).installments[1].lateCents, 2500);
});
test("rejects corrupt schedule amounts rather than displaying misleading debt", () => {
  const row = loanRow(); setDay(row, "2026-10-02", {unpaid: true, base: 900}); assert.throws(() => parseLoan(row, asOf));
});
test("rejects a future collection date", () => {
  const row = loanRow(); setDay(row, "2026-10-01", {unpaid: false, paidOn: "2026-10-28"}); assert.throws(() => parseLoan(row, asOf));
});
test("deleted loans are excluded and guarantees are not returned to the customer", () => {
  const row = loanRow(); assert.ok(!JSON.stringify(parseLoan(row, asOf)).includes("Garantía privada"));
  row[14] = "ELIMINADO"; assert.equal(parseLoan(row, asOf), null);
});
test("decimal money is converted to integer cents without common binary rounding artifacts", () => {
  assert.equal(money(.29), 29); assert.equal(money(1.005), 101); assert.equal(money("502.20"), 50220);
  assert.throws(() => money(-1)); assert.throws(() => money("")); assert.throws(() => money(Infinity));
});
async function sheetsFixture(moved = false) {
  const bytes = new Uint8Array(await crypto.subtle.exportKey("pkcs8", signingKey.privateKey));
  const pem = "-----BEGIN PRIVATE KEY-----\n" + btoa(String.fromCharCode(...bytes)) + "\n-----END PRIVATE KEY-----";
  const env = {SPREADSHEET_ID: "fixture-spreadsheet-1234567890", SHEETS_SERVICE_ACCOUNT_JSON: JSON.stringify({client_email: "reader@fixture.iam.gserviceaccount.com", private_key: pem})};
  const A = loanRow(), B = loanRow({id: "2026-002", client: "Bruno"}); const requested = [];
  const fetcher = async (url, options) => {
    if (String(url) === "https://oauth2.googleapis.com/token") {
      const payload = JSON.parse(Buffer.from(options.body.get("assertion").split('.')[1], 'base64url').toString());
      assert.equal(payload.scope, "https://www.googleapis.com/auth/spreadsheets.readonly");
      return new Response(JSON.stringify({access_token: "read-only-fixture-token", expires_in: 3600}));
    }
    assert.equal(options.method || "GET", "GET"); assert.equal(options.headers.Authorization, "Bearer read-only-fixture-token");
    const ranges = new URL(url).searchParams.getAll("ranges"); requested.push(...ranges);
    return new Response(JSON.stringify({spreadsheetId: env.SPREADSHEET_ID, valueRanges: ranges.length === 2 ? [
      {values: [["CP_SIMPLE_V2"], ["CAPITAL INICIAL", 0], ["REVISION", "test"], ["CALENDARIO DESDE", "2026-01-01"], ["CALENDARIO HASTA", "2026-12-31"]]},
      {values: [HEADERS, A.slice(0, 16), B.slice(0, 16)]}
    ] : [{values: [moved ? B : A]}]}));
  };
  return {env, fetcher, requested};
}
test("the Sheets bridge obtains read-only access and fetches full calendar rows only for assigned loans", async () => {
  const f = await sheetsFixture(); const loans = await readAuthorizedLoans(f.env, ["2026-001"], f.fetcher);
  assert.deepEqual(loans.map(item => item.id), ["2026-001"]);
  assert.ok(f.requested.some(range => range.startsWith("'PRESTAMOS'!A2:")));
  assert.ok(!f.requested.some(range => range.startsWith("'PRESTAMOS'!A3:")));
  assert.ok(!JSON.stringify(loans).includes("Bruno"));
});
test("a row moved during reading cannot leak the replacement borrower's financial details", async () => {
  const f = await sheetsFixture(true);
  await assert.rejects(() => readAuthorizedLoans(f.env, ["2026-001"], f.fetcher), error => error.code === "BOOK_CHANGED");
});
test("invalid service private key returns an actionable error without disclosing the key", async () => {
  const {sheetsApi} = await import("../src/sheets.mjs");
  const env = {SPREADSHEET_ID: "fixtureSpreadsheetIdentifier123456", SHEETS_SERVICE_ACCOUNT_JSON: JSON.stringify({
    client_email: "test@fixture.iam.gserviceaccount.com", private_key: "-----BEGIN PRIVATE KEY-----\nnot-a-private-key\n-----END PRIVATE KEY-----"})};
  await assert.rejects(() => sheetsApi(env, "?fields=spreadsheetId", {}, async () => { throw Error("Unexpected network request"); }), error => {
    assert.equal(error.code, "SERVICE_KEY_INVALID");
    assert.ok(!error.message.includes("not-a-private-key")); return true;
  });
});

test("registered late fees remain distinct from automatically calculated overdue amounts", () => {
  const row = loanRow();
  let item = parseLoan(row, asOf).installments[0];
  assert.ok(item.lateCents > 0);
  assert.equal(item.recordedLateCents, 0);
  setDay(row, "2026-10-01", {unpaid: true, late: 2});
  item = parseLoan(row, asOf).installments[0];
  assert.equal(item.recordedLateCents, 200);
});
