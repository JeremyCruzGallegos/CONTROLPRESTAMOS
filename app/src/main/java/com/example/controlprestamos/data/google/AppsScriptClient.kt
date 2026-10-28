package com.example.controlprestamos.data.google

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** AuthorizationClient must supply a short-lived token in memory; no token is saved here. */
fun interface GoogleTokenProvider { fun accessToken(): String }

/** Requests and receipts must belong to the book selected by GoogleConnection. */
class AppsScriptClient(private val deploymentId: String, private val bookId: String, private val tokens: GoogleTokenProvider) {
    init {
        require(deploymentId.matches(Regex("[A-Za-z0-9_-]+")))
        SheetsIdentity.requireBook(bookId)
    }
    fun execute(request: JSONObject): JSONObject {
        require(request.optString("bookId") == bookId) { "Libro distinto del configurado" }
        val token = tokens.accessToken()
        require(token.isNotBlank()) { "Inicia sesión y autoriza el acceso a Google" }
        val connection = URL("https://script.googleapis.com/v1/scripts/$deploymentId:run").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000; connection.readTimeout = 380_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().put("function", "controlPrestamosApi").put("parameters", JSONArray().put(request)).put("devMode", false)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                        ?.let { JSONObject(it).optJSONObject("error")?.optString("message") }
                }.getOrNull()
                error(scriptFailureMessage(status, detail))
            }
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            response.optJSONObject("error")?.let { failure ->
                val details = failure.optJSONArray("details")
                val message = details?.let { array -> (0 until array.length()).firstNotNullOfOrNull { index ->
                    array.optJSONObject(index)?.optString("errorMessage")?.takeIf { it.isNotBlank() }
                } } ?: failure.optString("message")
                error(scriptFailureMessage(status, message))
            }
            val result = response.getJSONObject("response").getJSONObject("result")
            require(result.getString("bookId") == bookId) { "Respuesta de un libro distinto" }
            return result
        } finally { connection.disconnect() }
    }
}
