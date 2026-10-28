package com.example.controlprestamos.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executor
import java.util.UUID

/** The operating system handles credentials; this app never receives a PIN, pattern or biometric data. */
class DeviceAuthentication(private val activity: ComponentActivity,
    private val complete: (String, Boolean, String?) -> Unit) {
    private var request: SensitiveRequest? = null
    private var cancellation: CancellationSignal? = null
    private var credentialPending = false
    private var credentialRequestId: String? = null
    private val main = Executor { Handler(Looper.getMainLooper()).post(it) }
    private val credentials = activity.activityResultRegistry.register("device-auth-${UUID.randomUUID()}", activity, ActivityResultContracts.StartActivityForResult()) { result ->
        val id = credentialRequestId
        credentialRequestId = null; credentialPending = false
        if (request?.id != id) return@register
        finish(result.resultCode == Activity.RESULT_OK, if (result.resultCode == Activity.RESULT_OK) null else "Verificación cancelada. No se guardó el cambio.")
    }
    fun authenticate(value: SensitiveRequest) {
        if (request?.id == value.id) return
        cancel()
        request = value
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            finish(false, "Configura un PIN, patrón o contraseña de bloqueo en el teléfono para autorizar cambios.")
            return
        }
        if (Build.VERSION.SDK_INT < 28) { credential(value); return }
        biometric(value)
    }
    @android.annotation.TargetApi(28)
    private fun biometric(value: SensitiveRequest) {
        if (request?.id != value.id) return
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (request?.id == value.id) finish(true, null) }
            override fun onAuthenticationError(code: Int, text: CharSequence) {
                if (credentialPending || request?.id != value.id) return
                when (code) {
                    BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
                    BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
                    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT, BiometricPrompt.BIOMETRIC_ERROR_UNABLE_TO_PROCESS -> credential(value)
                    else -> finish(false, "Verificación cancelada. No se guardó el cambio.")
                }
            }
        }
        try {
            val builder = BiometricPrompt.Builder(activity).setTitle("Confirmar identidad")
                .setDescription(value.purpose)
            when {
                Build.VERSION.SDK_INT >= 30 -> builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                Build.VERSION.SDK_INT >= 29 -> builder.setDeviceCredentialAllowed(true)
                else -> builder.setNegativeButton("Usar PIN, patrón o contraseña", main) { _, _ -> credential(value) }
            }
            cancellation = CancellationSignal()
            builder.build().authenticate(cancellation!!, main, callback)
        } catch (_: Exception) { credential(value) }
    }
    @Suppress("DEPRECATION")
    private fun credential(value: SensitiveRequest) {
        if (request?.id != value.id || credentialPending) return
        if (credentialRequestId != null) { finish(false, "Espera a que termine la verificación anterior."); return }
        try {
            val intent: Intent = activity.getSystemService(KeyguardManager::class.java)
                .createConfirmDeviceCredentialIntent("Confirmar identidad", value.purpose)
                ?: return finish(false, "No se pudo abrir el bloqueo del teléfono. No se guardó el cambio.")
            credentialPending = true; credentialRequestId = value.id
            cancellation?.cancel(); cancellation = null
            credentials.launch(intent)
        } catch (_: Exception) {
            credentialPending = false
            finish(false, "No se pudo verificar la identidad. No se guardó el cambio.")
        }
    }
    private fun finish(success: Boolean, error: String?) {
        val value = request ?: return
        request = null; cancellation = null; credentialPending = false
        complete(value.id, success && !activity.isFinishing && !activity.isDestroyed, error)
    }
    fun cancel() {
        val value = request
        request = null; credentialPending = false
        cancellation?.cancel(); cancellation = null
        value?.let { complete(it.id, false, "Verificación cancelada. No se guardó el cambio.") }
    }
}
