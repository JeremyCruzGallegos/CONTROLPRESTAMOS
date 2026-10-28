package com.example.controlprestamos.notifications

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable internal fun NotificationSettingsDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(CollectionNotifications.load(context)) }
    var permission by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    fun update(value: ReminderSettings) { settings = value; CollectionNotifications.save(context, value) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Notificaciones de cobros") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recordarme los cobros de hoy", Modifier.weight(1f))
                Switch(settings.enabled, { enabled -> update(settings.copy(enabled = enabled))
                    if (enabled && !permission && Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) })
            }
            if (!permission) {
                Text("Android tiene desactivados los avisos de esta app.")
                OutlinedButton(onClick = { if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Permitir notificaciones") }
            }
            Text("Cantidad de avisos al día")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..4).forEach { count -> FilterChip(settings.count == count, {
                    update(settings.copy(count = count, hour = if (count > 1) minOf(settings.hour, 21) else settings.hour,
                        endHour = if (count > 1) maxOf(settings.endHour, minOf(settings.hour, 21) + 1) else settings.endHour))
                }, label = { Text("$count") }) }
            }
            OutlinedButton(onClick = {
                TimePickerDialog(context, { _, hour, minute -> update(settings.copy(hour = hour, minute = minute,
                    count = if (hour == 23) 1 else settings.count, endHour = maxOf(settings.endHour, minOf(hour + 1, 23)))) }, settings.hour, settings.minute, true).show()
            }, modifier = Modifier.fillMaxWidth()) { Text("Primer aviso · %02d:%02d".format(settings.hour, settings.minute)) }
            if (settings.count > 1) {
                Text("Último aviso · ${settings.endHour}:00")
                if (settings.hour < 22) Slider(settings.endHour.toFloat(), { update(settings.copy(endHour = it.toInt())) },
                    valueRange = (settings.hour + 1).toFloat()..23f, steps = (22 - settings.hour).coerceAtLeast(0))
            }
            Text("Horarios: " + settings.times().joinToString(" · ") { "%02d:%02d".format(it.hour, it.minute) })
            Text("Días de aviso")
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf("L", "M", "X", "J", "V", "S", "D").forEachIndexed { index, name ->
                    val day = index + 1
                    FilterChip(day in settings.days, { update(settings.copy(days = if (day in settings.days) settings.days - day else settings.days + day)) },
                        label = { Text(name) }, modifier = Modifier.weight(1f))
                }
            }
            if (settings.days.isEmpty()) Text("No hay días seleccionados; los avisos están pausados.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Solo si quedan cobros pendientes", Modifier.weight(1f))
                Switch(settings.onlyPending, { update(settings.copy(onlyPending = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ocultar nombres y montos", Modifier.weight(1f))
                Switch(settings.privateContent, { update(settings.copy(privateContent = it)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Aviso con sonido", Modifier.weight(1f))
                Switch(settings.sound, { update(settings.copy(sound = it)) })
            }
            Text("Los avisos funcionan sin internet con los datos del teléfono. Android puede retrasarlos por ahorro de batería. Al tocar un aviso se abrirán los pagos de hoy.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) {
                Text("Sonido, vibración y permisos de Android")
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Listo") } })
}

@Composable internal fun RequestCollectionNotificationPermission(ready: Boolean, syncOverdue: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("reminders", 0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(ready, syncOverdue) {
        if (ready && Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("permissionAsked", false) && (CollectionNotifications.load(context).enabled || syncOverdue)) {
            prefs.edit().putBoolean("permissionAsked", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ready) CollectionNotifications.schedule(context)
    }
}
