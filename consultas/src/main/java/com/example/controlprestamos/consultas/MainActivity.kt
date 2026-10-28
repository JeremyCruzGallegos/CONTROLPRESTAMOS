package com.example.controlprestamos.consultas

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.*
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private var paymentEntry by mutableIntStateOf(0)
    private val model by lazy { ViewModelProvider(this)[ConsultasViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if(intent.getBooleanExtra("open_payments",false))paymentEntry++
        model.acceptCallback(intent?.data)
        setContent {
            val state by model.state.collectAsState()
            LaunchedEffect(state.profile?.sub,state.profile?.authorized,state.busy){ConsultationReminders.observe(this@MainActivity,state.profile,state.busy)}
            ConsultasApp(model,paymentEntry) {
            model.startLogin()?.let { uri -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                .onFailure { model.cancelLogin(); model.error("No se pudo abrir el navegador para conectar con Google.") } }
        } }
    }
    override fun onResume() {
        super.onResume()
        if (model.state.value.profile != null && !model.state.value.busy && !model.state.value.connecting) model.refresh()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); model.acceptCallback(intent.data);if(intent.getBooleanExtra("open_payments",false))paymentEntry++ }
}
