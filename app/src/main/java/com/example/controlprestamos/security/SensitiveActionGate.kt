package com.example.controlprestamos.security

/** One prompt authorizes one immutable action for one account/book, never a general unlocked session. */
data class SensitiveRequest(val id: String, val purpose: String)
class SensitiveActionGate(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Pending(val request: SensitiveRequest, val owner: String, val created: Long, val action: () -> Unit)
    private var pending: Pending? = null
    fun request(id: String, purpose: String, owner: String, action: () -> Unit): SensitiveRequest {
        check(pending == null) { "Termina la verificación de identidad pendiente" }
        return SensitiveRequest(id, purpose).also { pending = Pending(it, owner, now(), action) }
    }
    fun resolve(id: String, owner: String, authenticated: Boolean): Boolean {
        val value = pending?.takeIf { it.request.id == id } ?: return false
        pending = null
        if (!authenticated || value.owner != owner || now() - value.created !in 0..120_000) return false
        value.action()
        return true
    }
    fun invalidate() { pending = null }
}
