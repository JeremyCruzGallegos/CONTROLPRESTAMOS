import {ApiError} from "./security.mjs";
import {sheetsApi, SheetsFailure, todayLima} from "./sheets.mjs";
export const CLIENT_HEADERS = ["CÓDIGO CLIENTE","CLIENTE","FECHA DE ALTA","PRÉSTAMOS","VISUALIZACIÓN","CUENTA GOOGLE"];
export const CLIENT_CODE = /^CP-(?:[0-9A-F]{20}|[0-9]{8}-[A-Z]{1,4}-[0-9A-F]{8})$/;
const revision = () => Array.from(crypto.getRandomValues(new Uint8Array(16)), n=>n.toString(16).padStart(2,"0")).join("");
export function newCustomerCode(name="Cliente",date=todayLima()) {
  const initials=String(name).normalize("NFD").replace(/\p{M}/gu,"").trim().split(/\s+/).map(word=>word[0]).filter(letter=>/^[a-z]$/i.test(letter)).slice(0,4).join("").toUpperCase() || "CL";
  return `CP-${date.replace(/-/g,"")}-${initials}-${revision().slice(0,8).toUpperCase()}`;
}
function cells(sheetId,row,column,values){return {updateCells:{start:{sheetId,rowIndex:row,columnIndex:column},rows:[{values:values.map(value=>({userEnteredValue:{stringValue:String(value)}}))}],fields:"userEnteredValue"}};}
function guard(sheetId,id){const name="cp_clientes_"+id;return {addNamedRange:{namedRange:{name,namedRangeId:name,range:{sheetId,startRowIndex:0,endRowIndex:1,startColumnIndex:7,endColumnIndex:8}}}};}
const invalid=()=>new ApiError(503,"CLIENT_DIRECTORY_INVALID","Revisa la pestaña CLIENTES del libro antes de continuar.");
export class ClientDirectory {
  constructor(env,fetcher=fetch){this.env=env;this.fetcher=fetcher;}
  api(suffix,options={}){return sheetsApi(this.env,suffix,options,this.fetcher);}
  async read(){
    const meta=await this.api("?fields="+encodeURIComponent("spreadsheetId,sheets(properties(sheetId,title,gridProperties))"));
    const props=meta.sheets?.map(item=>item.properties).find(item=>item.title==="CLIENTES");
    if(!props)return {meta,props:null,entries:[],revision:null};
    if(props.gridProperties.columnCount<8 || props.gridProperties.rowCount>100000)throw invalid();
    const rows=(await this.api("/values/"+encodeURIComponent(`'CLIENTES'!A1:H${Math.min(props.gridProperties.rowCount,5001)}`)+"?valueRenderOption=FORMULA")).values || [];
    if(!CLIENT_HEADERS.every((header,i)=>rows[0]?.[i]===header) || !/^[0-9a-f]{32}$/.test(rows[0]?.[7] || ""))throw invalid();
    const entries=[],codes=new Set(),loans=new Set();
    for(let i=1;i<rows.length;i++){
      const row=rows[i];if(!row.some(value=>value!=="" && value!=null))continue;
      const [code,name,date]=row;const ids=String(row[3] || "").split(',').map(id=>id.trim()).filter(Boolean);
      if(!CLIENT_CODE.test(code) || codes.has(code) || typeof name!=="string" || !name.trim() || name.length>200 || !/^\d{4}-\d{2}-\d{2}$/.test(date) ||
        ids.length>100 || ids.some(id=>!/^20\d{2}-\d{3,}$/.test(id) || loans.has(id)))throw invalid();
      codes.add(code);for(const id of ids)loans.add(id);
      entries.push({row:i+1,code,name,date,loanIds:ids});
    }
    return {meta,props,entries,revision:rows[0][7]};
  }
  async mirror(customer,email=""){
    for(let attempt=0;attempt<4;attempt++){
      const snapshot=await this.read();const requests=[];let sheetId=snapshot.props?.sheetId;
      if(!snapshot.props){
        sheetId=190027;while(snapshot.meta.sheets.some(item=>item.properties.sheetId===sheetId))sheetId++;
        requests.push({addSheet:{properties:{sheetId,title:"CLIENTES",gridProperties:{rowCount:100,columnCount:8,frozenRowCount:1}}}},guard(sheetId,"init_v1"),cells(sheetId,0,0,CLIENT_HEADERS));
        requests.push({repeatCell:{range:{sheetId,startRowIndex:0,endRowIndex:1,startColumnIndex:0,endColumnIndex:6},cell:{userEnteredFormat:{textFormat:{bold:true}}},fields:"userEnteredFormat.textFormat.bold"}},
          {updateDimensionProperties:{range:{sheetId,dimension:"COLUMNS",startIndex:6,endIndex:8},properties:{hiddenByUser:true},fields:"hiddenByUser"}});
      }else requests.push(guard(sheetId,"rev_"+snapshot.revision));
      const entry=snapshot.entries.find(item=>item.code===customer.code);
      if(!entry && snapshot.entries.some(other=>other.loanIds.some(id=>customer.loanIds.includes(id))))throw new ApiError(409,"CLIENT_CODE_CHANGED","El código del cliente cambió en la hoja. Actualiza Préstamos y vuelve a abrir el nombre.");
      const row=entry?.row || Math.max(1,...snapshot.entries.map(item=>item.row))+1;
      if(snapshot.props && row>snapshot.props.gridProperties.rowCount)requests.push({appendDimension:{sheetId,dimension:"ROWS",length:row+20-snapshot.props.gridProperties.rowCount}});
      const ids=[...new Set([...(entry?.loanIds || []),...customer.loanIds])].sort();
      if(!entry || ids.join(',')!==entry.loanIds.join(',') || customer.name!==entry.name)requests.push(cells(sheetId,row-1,0,[customer.code,customer.name,entry?.date || (customer.code.match(/^CP-(\d{4})(\d{2})(\d{2})-/)?.slice(1).join("-") || new Date(customer.created_at*1000).toISOString().slice(0,10)),ids.join(', ')]));
      requests.push(cells(sheetId,row-1,4,[customer.enabled?"PERMITIDA":"DESHABILITADA",email]),cells(sheetId,0,7,[revision()]));
      try{await this.api(":batchUpdate",{method:"POST",write:true,body:{requests}});return;}
      catch(error){if(!(error instanceof SheetsFailure && error.googleStatus===400 && /already exists|already been used|already.*sheet/i.test(error.details)))throw error;}
    }
    throw new ApiError(409,"CLIENT_DIRECTORY_CHANGED","La lista de clientes cambió. Actualiza e intenta nuevamente.");
  }
}
export function importClientDirectory(state,entries){
  state.customers ||= {};
  for(const entry of entries){
    let previous=state.customers[entry.code];
    const overlaps=Object.values(state.customers).filter(item=>item.code!==entry.code && item.loanIds.some(id=>entry.loanIds.includes(id)));
    if(overlaps.length>1 || overlaps.some(item=>item.linkedSub && state.accounts[item.linkedSub]?.customerCode===item.code))throw new ApiError(409,"CLIENT_CODE_CHANGED","Hay códigos distintos para un cliente vinculado. Revisa CLIENTES antes de continuar.");
    for(const other of overlaps){previous ||= other;delete state.customers[other.code];}
    const customer={code:entry.code,name:entry.name,loanIds:entry.loanIds,enabled:previous?.enabled || false,linkedSub:previous?.linkedSub || null,
      created_at:previous?.created_at || Math.floor(Date.parse(entry.date+"T12:00:00Z")/1000)};
    state.customers[entry.code]=customer;
    if(customer.linkedSub)for(const id of customer.loanIds)state.grants[id]={sub:customer.linkedSub,granted_at:previous?.created_at || customer.created_at};
  }
}
