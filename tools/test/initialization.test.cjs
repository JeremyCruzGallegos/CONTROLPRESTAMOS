const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync(require('node:path').join(__dirname,'../InicializarLibro.gs'),'utf8');
function fixture(occupied=false){
 const tabs=[],writes=[];
 function sheet(name){const s={name,rows:1000,cols:26,setName(n){this.name=n},getLastRow(){return occupied?1:0},getLastColumn(){return occupied?1:0},getMaxRows(){return this.rows},getMaxColumns(){return this.cols},deleteRows(a,n){this.rows-=n},insertRowsAfter(a,n){this.rows+=n},deleteColumns(a,n){this.cols-=n},insertColumnsAfter(a,n){this.cols+=n},setFrozenRows(){},setFrozenColumns(){},setColumnWidth(){},getRange(...key){const range={setValues(value){writes.push({sheet:s.name,key,value});return this},setValue(value){writes.push({sheet:s.name,key,value});return this},setFormula(value){writes.push({sheet:s.name,key,value});return this},setNumberFormat(){return this},setBackground(){return this},setFontColor(){return this},setFontWeight(){return this}};return range}};tabs.push(s);return s}
 sheet('Sheet1');const book={getSheets:()=>tabs,insertSheet:sheet,setSpreadsheetTimeZone(){},setSpreadsheetLocale(){},getId:()=> 'EXAMPLE_NEW_BOOK_ID_00000001'};
 const ctx={SpreadsheetApp:{getActiveSpreadsheet:()=>book,flush(){}},Utilities:{getUuid:()=> '01234567-89ab-cdef-0123-456789abcdef'},console:{log(){}},Date};vm.createContext(ctx);vm.runInContext(source,ctx);return{tabs,writes,run:()=>ctx.inicializarLibroVacio()}
}
const empty=fixture();empty.run();assert.deepEqual(empty.tabs.map(s=>s.name),['NUEVO','PRESTAMOS','CLIENTES']);
assert.equal(empty.writes.find(w=>w.key[0]==='A1:B5').value[1][1],0);
const header=empty.writes.find(w=>w.sheet==='PRESTAMOS'&&w.key[0]===7);assert.equal(header.value[0].length,empty.tabs[1].cols);assert.equal(header.value[0][16],'01 ENE 2026\nNO PAGÓ');
assert(!empty.writes.some(w=>w.sheet==='PRESTAMOS'&&typeof w.key[0]==='number'&&w.key[0]>=8));
const occupied=fixture(true);assert.throws(occupied.run,/completamente vacía/);assert.equal(occupied.writes.length,0);
console.log('OK: libro vacío, encabezados compatibles y rechazo de libro con datos.');
