package com.example.controlprestamos.consultas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

internal data class ConsultasConfig(val origin: String, val supportEmail: String) {
    val configured get() = origin.isNotEmpty()
    companion object {
        fun load(context: Context): ConsultasConfig {
            val value = JSONObject(context.resources.openRawResource(R.raw.consultas_connection).bufferedReader().use { it.readText() })
            val origin = value.optString("apiBaseUrl").trimEnd('/')
            if (origin.isNotEmpty()) {
                val uri = URI(origin)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty())
            }
            return ConsultasConfig(origin, value.getString("supportEmail"))
        }
    }
}

/** Only encrypted opaque app sessions and the short-lived PKCE attempt are persisted. */
internal class SecureSession(context: Context) {
    private val prefs = context.getSharedPreferences("consultas-private", 0)
    private val alias = context.packageName + ".session"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val parts = stored.split('.'); require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
            cipher.updateAAD((alias + ":" + name).toByteArray())
            String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8)
        }.getOrElse { prefs.edit().remove(name).commit(); null }
    }
    @Synchronized fun write(name: String, value: String?) {
        if (value == null) { check(prefs.edit().remove(name).commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD((alias + ":" + name).toByteArray())
        val encrypted = Base64.getEncoder().encodeToString(cipher.iv) + "." + Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray()))
        check(prefs.edit().putString(name, encrypted).commit())
    }
}

internal class ConsultasFailure(val status: Int, val code: String, message: String) : Exception(message)
internal class ConsultasApi(val config: ConsultasConfig) {
    fun request(path: String, session: String? = null, method: String = "GET", body: JSONObject? = null): JSONObject {
        check(config.configured) { "El servicio todavía no está configurado." }
        require(path.startsWith('/') && !path.contains("..") && !path.contains('?'))
        val connection = URL(config.origin + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method; connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 25000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            session?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (output.size() <= 8 * 1024 * 1024) {
                    val count = input.read(buffer); if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            if (bytes.size > 8 * 1024 * 1024) throw ConsultasFailure(503, "TOO_LARGE", "Hay demasiados datos para mostrar. Contacta con el prestamista.")
            val response = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            if (status !in 200..299) {
                val error = response?.optJSONObject("error")
                throw ConsultasFailure(status, error?.optString("code") ?: "SERVICE_ERROR", error?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "No se pudo conectar con el servicio. Reintenta más tarde.")
            }
            return response ?: throw ConsultasFailure(503, "INVALID_RESPONSE", "El servicio devolvió una respuesta incorrecta.")
        } finally { connection.disconnect() }
    }
}
