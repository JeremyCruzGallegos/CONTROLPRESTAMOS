package com.example.controlprestamos.data.google

/** Copy operations are integration tests; production remains blocked until a separate rollout. */
internal fun copyApiConfigured(book: String, production: String, copy: String, deployment: String,
    scopes: List<String>): Boolean = book == copy && book != production && book != SheetsIdentity.PRODUCTION_ID &&
    copy.matches(Regex("[A-Za-z0-9_-]{20,}")) && deployment.matches(Regex("[A-Za-z0-9_-]{20,}")) &&
    scopes.isNotEmpty() && scopes.all { it.matches(Regex("https://www\\.googleapis\\.com/auth/[A-Za-z0-9._/-]+")) }

internal fun paymentApiConfigured(book: String, production: String, copy: String, deployment: String,
    scopes: List<String>): Boolean = copyApiConfigured(book, production, copy, deployment, scopes) ||
    (book == production && production == SheetsIdentity.PRODUCTION_ID &&
        deployment.matches(Regex("[A-Za-z0-9_-]{20,}")) && scopes.isNotEmpty() &&
        scopes.all { it.matches(Regex("https://www\\.googleapis\\.com/auth/[A-Za-z0-9._/-]+")) })

/** Public errors only; never include stack traces, HTTP payloads or authorization tokens. */
internal fun scriptFailureMessage(status: Int, detail: String?): String {
    val clean = detail?.replace(Regex("[\\p{Cntrl}]"), " ")?.trim()?.take(240).orEmpty()
    val guidance = when {
        clean.contains("function not found", ignoreCase = true) ->
            "Falta controlPrestamosApi en la versión desplegada. Sube el paquete del libro configurado y publica una nueva versión del Ejecutable de API."
        status == 401 -> "La autorización caducó. Reconecta Google y reintenta la misma operación."
        status == 403 -> "Revisa los permisos del despliegue y que Apps Script y el cliente Android usen el mismo proyecto Cloud."
        status == 404 -> "No se encuentra el Ejecutable de API. Comprueba el ID y el tipo de implementación en Apps Script."
        status == 429 -> "Google limitó las operaciones. Espera un momento y reintenta la misma operación."
        status !in 200..299 -> "Google respondió HTTP $status. Conserva la operación pendiente y reinténtala."
        else -> "Apps Script rechazó la operación."
    }
    return if (clean.isEmpty()) guidance else "$guidance Detalle: $clean"
}
