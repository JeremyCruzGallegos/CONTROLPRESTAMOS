import {test} from 'node:test';
import assert from 'node:assert/strict';
import {serviceCredentials, audience, claims, signedToken} from './helpers.mjs';
import {FakeSheets} from './fake-sheets.mjs';
import {sheetsApi} from '../src/sheets.mjs';
import {verifyGoogleIdToken} from '../src/security.mjs';
const book = 'YOUR_SPREADSHEET_ID_REPLACE_ME';
const environment = () => ({PUBLIC_ORIGIN:'https://fixture.workers.dev',GOOGLE_CLIENT_ID:audience,
  GOOGLE_CLIENT_SECRET:'fixture',ADMIN_EMAIL:'owner@gmail.com',SHEETS_SERVICE_ACCOUNT_JSON:serviceCredentials,SPREADSHEET_ID:book});

test('generated Worker reaches Google login using only supported Cloudflare redirect modes', async () => {
  const previous = globalThis.fetch, sheets = new FakeSheets(book), requests = [];
  globalThis.fetch = async (url, options) => {
    if (options?.redirect === 'error') throw new TypeError('Invalid redirect value');
    assert.equal(options?.redirect, 'manual'); requests.push(String(url));
    return sheets.fetch(url, options);
  };
  try {
    const {default: worker} = await import('../dist/worker.mjs');
    const env = environment();
    const response = await worker.fetch(new Request(env.PUBLIC_ORIGIN+'/auth/start?state='+'A'.repeat(43)+'&code_challenge='+'B'.repeat(43)),env);
    assert.equal(response.status,302);
    assert.equal(new URL(response.headers.get('location')).origin,'https://accounts.google.com');
    assert.ok(requests.some(url => url.startsWith('https://oauth2.googleapis.com/token')));
    assert.ok(requests.some(url => url.includes(':batchUpdate')));
  } finally { globalThis.fetch = previous; }
});
test('service credentials are never forwarded to a redirected token endpoint', async () => {
  let requests = 0;
  await assert.rejects(() => sheetsApi(environment(), '?fields=spreadsheetId', {}, async (_url, options) => {
    requests++; assert.equal(options.redirect,'manual');
    return new Response(null,{status:302,headers:{Location:'https://untrusted.invalid'}});
  }), error => error.code === 'SHEETS_UNAVAILABLE');
  assert.equal(requests,1);
});
test('redirected Google signing keys are rejected without following the redirect', async () => {
  let requests = 0;
  await assert.rejects(() => verifyGoogleIdToken(awaitToken, audience, 'fixture-nonce', async (_url, options) => {
    requests++; assert.equal(options.redirect,'manual');
    return new Response(null,{status:302,headers:{Location:'https://untrusted.invalid'}});
  }), error => error.code === 'GOOGLE_UNAVAILABLE');
  assert.equal(requests,1);
});
const awaitToken = await signedToken(claims());
