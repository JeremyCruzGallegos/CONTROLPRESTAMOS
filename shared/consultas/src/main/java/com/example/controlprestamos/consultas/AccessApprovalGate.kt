package com.example.controlprestamos.consultas

/** A successful device prompt authorizes one captured change for one account/session. */
internal class AccessApprovalGate(private val now: () -> Long) {
    private data class Pending(val id:String,val owner:String,val started:Long,val action:()->Unit)
    private var pending:Pending?=null
    fun request(id:String,owner:String,action:()->Unit):Boolean {
        if(pending!=null)return false
        pending=Pending(id,owner,now(),action);return true
    }
    fun resolve(id:String,owner:String,authenticated:Boolean):Boolean {
        val value=pending?.takeIf{it.id==id} ?: return false
        pending=null
        if(!authenticated || owner!=value.owner || now()-value.started !in 0..120_000)return false
        value.action();return true
    }
    fun cancel(){pending=null}
}
