import {test} from 'node:test';
import assert from 'node:assert/strict';
import {ClientDirectory,newCustomerCode,importClientDirectory} from '../src/directory.mjs';
import {FakeSheets} from './fake-sheets.mjs';
import {serviceCredentials} from './helpers.mjs';
function fixture(){const env={SPREADSHEET_ID:'fixture-directory-spreadsheet-123',SHEETS_SERVICE_ACCOUNT_JSON:serviceCredentials};const sheets=new FakeSheets(env.SPREADSHEET_ID);return {sheets,directory:new ClientDirectory(env,sheets.fetch.bind(sheets))};}
const customer=(name,id,code)=>({code:code || newCustomerCode(name,'2026-10-27'),name,loanIds:[id],enabled:false,linkedSub:null,created_at:1793102400});
test('code contains loan date and normalized client initials',()=>{assert.match(newCustomerCode('José Cruz','2026-10-27'),/^CP-20261027-JC-[0-9A-F]{8}$/);});
test('visible CLIENTES directory preserves the financial sheets and displays code and loan IDs',async()=>{
 const f=fixture(),record=customer('José Cruz','2026-001');const financial=new Map(f.sheets.cells);
 await f.directory.mirror(record);const result=await f.directory.read();assert.equal(result.entries[0].code,record.code);assert.deepEqual(result.entries[0].loanIds,['2026-001']);
 assert.deepEqual(f.sheets.cells,financial);assert.equal(f.sheets.directoryCells.get('1:4'),'DESHABILITADA');
 record.enabled=true;await f.directory.mirror(record,'cliente@gmail.com');assert.equal(f.sheets.directoryCells.get('1:4'),'PERMITIDA');assert.equal(f.sheets.directoryCells.get('1:5'),'cliente@gmail.com');
});
test('concurrent directory appends cannot overwrite another customer',async()=>{
 const f=fixture();await f.directory.mirror(customer('Uno','2026-001'));
 const two=customer('Dos','2026-002'),three=customer('Tres','2026-003');await Promise.all([f.directory.mirror(two),f.directory.mirror(three)]);
 const result=await f.directory.read();assert.equal(result.entries.length,3);assert.ok(result.entries.some(entry=>entry.code===two.code));assert.ok(result.entries.some(entry=>entry.code===three.code));
});
test('directory import adds new loans to a previously approved client without generating another code',()=>{
 const sub='100000000001',code=newCustomerCode('Ana','2026-10-27');const state={accounts:{[sub]:{customerCode:code}},customers:{[code]:{...customer('Ana','2026-001',code),enabled:true,linkedSub:sub}},grants:{}};
 importClientDirectory(state,[{code,name:'Ana',date:'2026-10-27',loanIds:['2026-001','2026-002']}]);assert.equal(state.customers[code].enabled,true);assert.equal(state.grants['2026-002'].sub,sub);
});
test('import cannot silently replace a code already claimed by a Google account',()=>{
 const sub='100000000001',old=newCustomerCode('Ana','2026-10-27'),next=newCustomerCode('Ana','2026-10-27');const state={accounts:{[sub]:{customerCode:old}},customers:{[old]:{...customer('Ana','2026-001',old),enabled:true,linkedSub:sub}},grants:{}};
 assert.throws(()=>importClientDirectory(state,[{code:next,name:'Ana',date:'2026-10-27',loanIds:['2026-001']}]),error=>error.code==='CLIENT_CODE_CHANGED');
});
