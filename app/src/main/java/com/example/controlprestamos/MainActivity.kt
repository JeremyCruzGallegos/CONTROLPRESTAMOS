package com.example.controlprestamos

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModelProvider
import com.example.controlprestamos.data.google.GoogleAuthorization
import com.example.controlprestamos.ui.*
import com.example.controlprestamos.ui.google.*
import com.example.controlprestamos.ui.theme.*
import kotlinx.coroutines.delay
import com.example.controlprestamos.consultas.ConsultasViewModel
import com.example.controlprestamos.security.SensitiveRequest
import com.example.controlprestamos.security.DeviceAuthentication

class MainActivity : ComponentActivity() {
    private val customerAccessModel by lazy { ViewModelProvider(this)[ConsultasViewModel::class.java] }
    private var deviceAuthentication: DeviceAuthentication? = null
    private var paymentNotification by mutableIntStateOf(0)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        customerAccessModel.acceptCallback(intent.data)
        if (intent.getBooleanExtra("open_payments", false)) paymentNotification++
    }
    override fun onDestroy() { deviceAuthentication?.cancel(); super.onDestroy() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("open_payments", false)) paymentNotification++
        enableEdgeToEdge()
        val demoModel by lazy { ViewModelProvider(this)[LoansViewModel::class.java] }
        val sheetsModel = ViewModelProvider(this)[SheetsViewModel::class.java]
        deviceAuthentication = DeviceAuthentication(this) { id, success, message ->
            if (customerAccessModel.sensitiveRequest.value?.id == id) customerAccessModel.deviceAuthenticationFinished(id, success, message)
            else sheetsModel.deviceAuthenticationFinished(id, success, message)
        }
        customerAccessModel.acceptCallback(intent?.data)
        val appearance = getSharedPreferences("appearance", MODE_PRIVATE)
        val connectionPreferences = getSharedPreferences("connection-mode", MODE_PRIVATE)
        val google = GoogleAuthorization(this)
        setContent {
            var dark by rememberSaveable { mutableStateOf(appearance.getBoolean("dark", true)) }
            var accentIndex by rememberSaveable { mutableIntStateOf(appearance.getInt("accent", 0).coerceIn(BrandAccents.indices)) }
            var mode by rememberSaveable { mutableStateOf(connectionPreferences.getString("mode", "google") ?: "google") }
            var brandingElapsed by rememberSaveable { mutableStateOf(false) }
            val state by sheetsModel.state.collectAsState()
            LaunchedEffect(Unit) { delay(900); brandingElapsed = true }
            val customerRequest by customerAccessModel.sensitiveRequest.collectAsState()
            LaunchedEffect(state.sensitiveRequest?.id, customerRequest?.id) {
                state.sensitiveRequest?.let { deviceAuthentication?.authenticate(it) }
                    ?: customerRequest?.let { deviceAuthentication?.authenticate(SensitiveRequest(it.id,it.purpose)) }
            }
            fun chooseMode(value: String) { mode = value; connectionPreferences.edit().putString("mode", value).apply() }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) google.finish(result.data, sheetsModel.scopes(), sheetsModel::authorized, sheetsModel::authorizationFailed)
                else sheetsModel.authorizationFailed("Autorización cancelada. Puedes volver a conectar Google.")
            }
            val accountPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                    if (email != null && sheetsModel.requestAccountChange(email)) chooseMode("google")
                }
            }
            fun changeAccount() {
                accountPicker.launch(AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null))
            }
            LaunchedEffect(mode, state.authEpoch) {
                if (mode == "google" && sheetsModel.beginAuthorization()) google.authorize(sheetsModel.authorizationEmail(), sheetsModel.scopes(), sheetsModel::authorized,
                    { pending -> if (!isDestroyed) launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build()) else sheetsModel.authorizationFailed("Vuelve a conectar Google") },
                    sheetsModel::authorizationFailed)
            }
            val loading = mode != "demo" && (!state.ready || !brandingElapsed)
            SideEffect {
                val bars = if (dark || loading) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            ControlPrestamosTheme(darkTheme = dark, accent = BrandAccents[accentIndex].second) {
                val toggleTheme = { dark = !dark; appearance.edit().putBoolean("dark", dark).apply(); Unit }
                val selectAccent: (Int) -> Unit = { accentIndex = it; appearance.edit().putInt("accent", it).apply() }
                DoubleBackToExit(mode) {
                    if (mode == "demo") {
                        val demo by demoModel.state.collectAsState()
                        if ((!brandingElapsed || demo.snapshot == null) && demo.error == null) WJenzoSplash(BrandAccents[accentIndex].second,
                            if (demo.snapshot == null) 0.3f else 1f)
                        else PrestamosApp(demoModel, dark, accentIndex, toggleTheme, selectAccent,
                            onGoogle = { chooseMode("google"); sheetsModel.retryAuthorization() }, onChangeAccount = ::changeAccount,
                            canChangeAccount = !state.busy && !state.saving && state.pending == null && state.pendingLoanEdit == null && state.edits.isEmpty())
                    } else if (!state.ready || !brandingElapsed || mode == "setup") {
                        WJenzoSplash(BrandAccents[accentIndex].second, state.progress, state.error, showRecovery = mode == "setup") {
                            if (!state.busy && (state.error != null || mode == "setup")) {
                                Button(onClick = { if (mode == "setup") chooseMode("google") else sheetsModel.retryAuthorization() }) { Text("Conectar / reintentar Google") }
                                if (state.cache != null) OutlinedButton(onClick = sheetsModel::useCache) { Text("Usar información guardada · Sin actualizar") }
                                TextButton(onClick = ::changeAccount, enabled = state.pending == null && state.pendingLoanEdit == null && state.edits.isEmpty()) { Text("Cambiar cuenta") }
                            }
                        }
                    } else SheetsApp(sheetsModel, dark, accentIndex, toggleTheme, selectAccent, notificationEntry = paymentNotification, onReconnect = sheetsModel::retryAuthorization, onChangeAccount = ::changeAccount,
                        onSignOut = {
                            val email = state.email; val scopes = sheetsModel.scopes()
                            if (sheetsModel.signOut()) {
                                chooseMode("setup")
                                if (email != null) google.revoke(email, scopes, {}, { sheetsModel.authorizationFailed("Sesión local cerrada. Google no confirmó la revocación; revísala en tu cuenta.") })
                            }
                        })
                }
            }
        }
    }
}

