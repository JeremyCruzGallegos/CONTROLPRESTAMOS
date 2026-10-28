package com.example.controlprestamos.data.google

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

internal val CLIENT_DIRECTORY_HEADERS = listOf("CÓDIGO CLIENTE", "CLIENTE", "FECHA DE ALTA", "PRÉSTAMOS", "VISUALIZACIÓN", "CUENTA GOOGLE")
internal val CLIENT_CODE_PATTERN = Regex("CP-(?:[0-9A-F]{20}|[0-9]{8}-[A-Z]{1,4}-[0-9A-F]{8})")
internal fun newClientCode(name:String,date:LocalDate,used:Set<String> = emptySet()):String {
    val initials=normalizedClient(name).split(Regex("\\s+")).mapNotNull{it.firstOrNull()?.takeIf{c->c in 'a'..'z'}}.take(4).joinToString("").uppercase().ifBlank{"CL"}
    repeat(20){
        val suffix=ByteArray(4).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02X".format(it)}
        val code="CP-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}-$initials-$suffix"
        if(code !in used)return code
    }
    error("No se pudo generar un código único; vuelve a intentar")
}
internal data class DirectoryClient(val row:Int,val code:String,val name:String,val date:String,val loans:List<String>)
internal data class ClientDirectoryPlan(val requests:List<JSONObject>,val code:String)
internal fun directoryCellRequest(sheet:Int,row:Int,column:Int,values:List<Any?>):JSONObject = JSONObject().put("updateCells",JSONObject()
    .put("start",JSONObject().put("sheetId",sheet).put("rowIndex",row).put("columnIndex",column))
    .put("rows",JSONArray().put(JSONObject().put("values",JSONArray(values.map{value->JSONObject().put("userEnteredValue",JSONObject().put("stringValue",value?.toString().orEmpty()))}))))
    .put("fields","userEnteredValue"))
internal fun directoryGuard(sheet:Int,revision:String):JSONObject=JSONObject().put("addNamedRange",JSONObject().put("namedRange",JSONObject()
    .put("name","cp_clientes_$revision").put("namedRangeId","cp_clientes_$revision")
    .put("range",JSONObject().put("sheetId",sheet).put("startRowIndex",0).put("endRowIndex",1).put("startColumnIndex",7).put("endColumnIndex",8))))
internal fun clientDirectoryPlan(records:List<SimpleRecord>,clientName:String,existingProperties:JSONObject?,values:List<List<Any?>>,sheetId:Int,
    legacy:JSONObject?=null):ClientDirectoryPlan {
    fun groups()=records.filterNot{it.deleted}.groupBy{normalizedClient(it.loan.client)}
    val requests=mutableListOf<JSONObject>();val nextRevision=UUID.randomUUID().toString().replace("-","")
    val clients=mutableListOf<DirectoryClient>()
    if(existingProperties==null){
        requests+=JSONObject().put("addSheet",JSONObject().put("properties",JSONObject().put("sheetId",sheetId).put("title","CLIENTES")
            .put("gridProperties",JSONObject().put("rowCount",maxOf(100,groups().size+20)).put("columnCount",8).put("frozenRowCount",1))))
        requests+=directoryGuard(sheetId,"init_v1")
        requests+=directoryCellRequest(sheetId,0,0,CLIENT_DIRECTORY_HEADERS)
        val legacyCustomers=legacy?.optJSONObject("customers")
        val old=legacyCustomers?.keys()?.asSequence()?.map{legacyCustomers.getJSONObject(it)}?.toList().orEmpty()
        groups().values.forEachIndexed{index,group->
            val ids=group.map{it.loan.id}.sorted();val name=group.first().loan.client;val date=group.minOf{it.loan.disbursed}
            val matches=old.filter{entry->entry.getJSONArray("loanIds").let{a->(0 until a.length()).any{a.getString(it) in ids}}}
            require(matches.size<=1){"Este grupo tiene varios códigos de cliente; revisa antes de crear el préstamo"}
            val code=matches.singleOrNull()?.getString("code")?.takeIf{CLIENT_CODE_PATTERN.matches(it)} ?: newClientCode(name,date,clients.map{it.code}.toSet())
            val entry=DirectoryClient(index+2,code,name,date.toString(),ids);clients+=entry
            val prior=matches.singleOrNull();val sub=prior?.optString("linkedSub")
            val email=sub?.let{legacy?.optJSONObject("accounts")?.optJSONObject(it)?.optString("email")}.orEmpty()
            requests+=directoryCellRequest(sheetId,index+1,0,listOf(code,name,date.toString(),ids.joinToString(", "),if(prior?.optBoolean("enabled")==true)"PERMITIDA" else "DESHABILITADA",email))
        }
        requests+=JSONObject().put("repeatCell",JSONObject().put("range",JSONObject().put("sheetId",sheetId).put("startRowIndex",0).put("endRowIndex",1).put("startColumnIndex",0).put("endColumnIndex",6))
            .put("cell",JSONObject().put("userEnteredFormat",JSONObject().put("textFormat",JSONObject().put("bold",true)))).put("fields","userEnteredFormat.textFormat.bold"))
        requests+=JSONObject().put("updateDimensionProperties",JSONObject().put("range",JSONObject().put("sheetId",sheetId).put("dimension","COLUMNS").put("startIndex",6).put("endIndex",8))
            .put("properties",JSONObject().put("hiddenByUser",true)).put("fields","hiddenByUser"))
    } else {
        require(values.firstOrNull()?.take(6)==CLIENT_DIRECTORY_HEADERS){"La pestaña CLIENTES tiene un formato diferente"}
        val revision=values.firstOrNull()?.getOrNull(7)?.toString().orEmpty();require(revision.matches(Regex("[0-9a-f]{32}"))){"La revisión de CLIENTES cambió"}
        values.drop(1).forEachIndexed{index,row->if(row.any{it!=null && it!=""}){
            val code=row.getOrNull(0)?.toString().orEmpty();val name=row.getOrNull(1)?.toString().orEmpty();val date=row.getOrNull(2)?.toString().orEmpty()
            val ids=row.getOrNull(3)?.toString().orEmpty().split(',').map{it.trim()}.filter{it.isNotEmpty()}
            require(CLIENT_CODE_PATTERN.matches(code) && name.isNotBlank() && ids.all{it.matches(Regex("20\\d{2}-\\d{3,}"))}){"Revisa los códigos en CLIENTES"}
            clients+=DirectoryClient(index+2,code,name,date,ids)
        }}
        require(clients.map{it.code}.distinct().size==clients.size && clients.flatMap{it.loans}.distinct().size==clients.sumOf{it.loans.size}){"Hay clientes o préstamos duplicados en CLIENTES"}
        requests+=directoryGuard(sheetId,"rev_$revision")
        val group=groups()[normalizedClient(clientName)].orEmpty();require(group.isNotEmpty())
        val ids=group.map{it.loan.id}.toSet()
        val matches=clients.filter{it.loans.any{loan->loan in ids} || normalizedClient(it.name)==normalizedClient(clientName)}
        require(matches.size<=1){"Los préstamos del cliente tienen varios códigos"}
        val old=matches.singleOrNull()
        val current=old?.copy(name=clientName,loans=(old.loans+ids).distinct().sorted()) ?: DirectoryClient((clients.maxOfOrNull{it.row} ?: 1)+1,newClientCode(clientName,group.minOf{it.loan.disbursed},clients.map{it.code}.toSet()),clientName,group.minOf{it.loan.disbursed}.toString(),ids.sorted())
        val rows=existingProperties.getJSONObject("gridProperties").getInt("rowCount")
        if(current.row>rows)requests+=JSONObject().put("appendDimension",JSONObject().put("sheetId",sheetId).put("dimension","ROWS").put("length",current.row+20-rows))
        requests+=directoryCellRequest(sheetId,current.row-1,0,listOf(current.code,current.name,current.date,current.loans.joinToString(", "))+
            if(old==null)listOf("DESHABILITADA","") else emptyList())
        clients.removeAll{it.code==current.code};clients+=current
    }
    requests+=directoryCellRequest(sheetId,0,7,listOf(nextRevision))
    val result=clients.single{normalizedClient(it.name)==normalizedClient(clientName)}
    return ClientDirectoryPlan(requests,result.code)
}
