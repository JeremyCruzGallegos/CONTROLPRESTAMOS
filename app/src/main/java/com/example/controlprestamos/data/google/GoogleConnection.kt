package com.example.controlprestamos.data.google

import android.app.Activity
import android.app.PendingIntent
import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.example.controlprestamos.R
import org.json.JSONObject
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.net.URL
import javax.net.ssl.HttpsURLConnection

const val READ_SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly"
const val WRITE_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
const val EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
data class GoogleConnection(val productionBookId: String, val testBookId: String, val deploymentId: String,
    val scriptScopes: List<String>, val copyWritesVerified: Boolean) {
    val readScopes = listOf(READ_SCOPE, EMAIL_SCOPE, "openid")
    fun scopes(copy: Boolean): List<String> {
        val editableScopes = listOf(WRITE_SCOPE, EMAIL_SCOPE, "openid")
        return if (copy && !usesDirectPayments(testBookId) && deploymentId.isNotBlank() && scriptScopes.isNotEmpty())
            (scriptScopes + editableScopes).distinct() else editableScopes
    }
    fun effectiveCopy(preference: Boolean) = !clientInstallation && preference
    fun canEditLoanText(book: String): Boolean = book == productionBookId || book == testBookId
    val clientInstallation: Boolean get() = productionBookId == SheetsIdentity.CLIENT_ID
    fun usesDirectPayments(book: String): Boolean = if (clientInstallation) book == productionBookId
        else productionBookId == SheetsIdentity.PRODUCTION_ID &&
            (book == productionBookId || (book == testBookId && SheetsIdentity.isSimpleBook(testBookId)))
    fun canDeleteLoan(book: String): Boolean = usesDirectPayments(book)
    fun canEditCapital(book: String): Boolean = usesDirectPayments(book)
    fun canWrite(book: String): Boolean = usesDirectPayments(book) || copyApiConfigured(book, productionBookId, testBookId, deploymentId, scriptScopes)
    fun canCreateLoan(book: String): Boolean = usesDirectPayments(book) || copyApiConfigured(book, productionBookId, testBookId, deploymentId, scriptScopes)
    fun financialReadOnlyReason(book: String): String? = when {
        book != testBookId && book != productionBookId -> "Este libro no está configurado para guardar pagos y moras."
        !canWrite(book) -> "Falta conectar el servicio de guardado de pagos y moras."
        else -> null
    }
    companion object {
        fun load(context: Context): GoogleConnection {
            val obj = JSONObject(context.resources.openRawResource(R.raw.google_connection).bufferedReader().use { it.readText() })
            val scopes = obj.getJSONArray("scriptScopes").let { array -> List(array.length()) { array.getString(it) } }
            val result = GoogleConnection(obj.getString("productionBookId"), obj.getString("testBookId"), obj.getString("deploymentId"), scopes, obj.getBoolean("copyWritesVerified"))
            require(result.productionBookId in setOf(SheetsIdentity.PRODUCTION_ID, SheetsIdentity.CLIENT_ID))
            if (result.clientInstallation) {
                require(result.testBookId.isBlank() && result.deploymentId.isBlank() && scopes.isEmpty())
            } else SheetsIdentity.requireCopy(result.testBookId)
            require(scopes.all { it.startsWith("https://www.googleapis.com/auth/") }) { "Scopes del manifiesto inválidos" }
            return result
        }
    }
}
class GoogleHttpException(val status: Int, message: String) : IllegalStateException(message)
interface SheetsHttp {
    fun get(url: String): JSONObject
    fun post(url: String, body: JSONObject): JSONObject
}
class GoogleHttp(private val tokens: GoogleTokenProvider) : SheetsHttp {
    override fun get(url: String): JSONObject = request(url, null)
    override fun post(url: String, body: JSONObject): JSONObject {
        require(url.matches(Regex("https://sheets\\.googleapis\\.com/v4/spreadsheets/[A-Za-z0-9_-]+:batchUpdate")))
        return request(url, body)
    }
    private fun request(url: String, body: JSONObject?): JSONObject {
        require(url.startsWith("https://sheets.googleapis.com/v4/") || url == "https://www.googleapis.com/oauth2/v3/userinfo")
        val token = tokens.accessToken(); require(token.isNotBlank()) { "Autoriza Google para actualizar" }
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = if (body == null) 20_000 else 90_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) throw GoogleHttpException(code, when (code) {
                401 -> "La autorización caducó. Vuelve a conectar Google."
                403 -> "Google rechazó el permiso. Reconecta y permite editar hojas; tu cuenta también debe tener acceso al libro."
                404 -> "No se encuentra el libro o no está compartido con esta cuenta."
                429 -> "Google limitó las consultas. Espera un momento y reintenta."
                else -> "Google respondió HTTP $code. Puedes reintentar la consulta u operación."
            })
            val text = connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder(); val buffer = CharArray(8192)
                while (true) { val size = reader.read(buffer); if (size < 0) break; result.append(buffer, 0, size); require(result.length <= 8_000_000) { "Respuesta demasiado grande; reduce la consulta" } }
                result.toString()
            }
            return JSONObject(text)
        } finally { connection.disconnect() }
    }
}

/** Google SDK authorization; tokens are kept only in the ViewModel's memory. */
class GoogleAuthorization(private val activity: Activity) {
    private val client get() = Identity.getAuthorizationClient(activity)
    private fun deliver(result: AuthorizationResult, scopes: List<String>, token: (String) -> Unit,
        resolution: (PendingIntent) -> Unit) {
        if (result.hasResolution()) resolution(requireNotNull(result.pendingIntent))
        else {
            require(result.grantedScopes.containsAll(scopes)) { "No concediste todos los permisos requeridos" }
            val access = result.accessToken
            require(!access.isNullOrBlank()) { "Google no entregó autorización de acceso" }
            token(access)
        }
    }
    fun authorize(email: String?, scopes: List<String>, token: (String) -> Unit,
        resolution: (PendingIntent) -> Unit, error: (String) -> Unit) {
        try {
            val builder = AuthorizationRequest.builder().setRequestedScopes(scopes.map(::Scope))
            if (!email.isNullOrBlank()) builder.setAccount(Account(email, "com.google"))
            client.authorize(builder.build())
                .addOnSuccessListener { result ->
                    runCatching { deliver(result, scopes, token, resolution) }.onFailure { error(failureMessage(it)) }
                }
                .addOnFailureListener { error(failureMessage(it)) }
        } catch (e: Exception) { error(failureMessage(e)) }
    }
    fun finish(intent: Intent?, scopes: List<String>, token: (String) -> Unit, error: (String) -> Unit) {
        try {
            require(intent != null) { "Autorización cancelada" }
            deliver(client.getAuthorizationResultFromIntent(intent), scopes, token) { error("Google requiere volver a autorizar") }
        } catch (e: Exception) { error(failureMessage(e)) }
    }
    fun revoke(email: String, scopes: List<String>, done: () -> Unit, error: (String) -> Unit) {
        try {
            val request = RevokeAccessRequest.builder().setAccount(Account(email, "com.google"))
                .setScopes(scopes.map(::Scope)).build()
            client.revokeAccess(request).addOnSuccessListener { done() }.addOnFailureListener { error(failureMessage(it)) }
        } catch (e: Exception) { error(failureMessage(e)) }
    }
    private fun failureMessage(error: Throwable): String = when (error) {
        is IllegalArgumentException -> error.message ?: "Revisa los permisos de Google"
        is ApiException -> "Google no pudo autorizar (código ${error.statusCode}). Revisa la cuenta y la configuración de acceso."
        else -> "Google no pudo autorizar la app. Revisa tu conexión e intenta nuevamente."
    }
}
