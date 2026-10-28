package com.example.controlprestamos.ui.google

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.R
import com.example.controlprestamos.data.google.SheetsIdentity
import com.example.controlprestamos.ui.FirstUseGuideProgress
import com.example.controlprestamos.ui.FirstUseTopic
import kotlinx.coroutines.delay

@Composable internal fun FirstUseGuides(book: String, catalogLoaded: Boolean, hasLoans: Boolean,
    topic: FirstUseTopic?, interactive: Boolean) {
    if (book != SheetsIdentity.CLIENT_ID) return
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("first-use-guides-v1", 0) }
    // The versioned guide key is deliberately independent of APK versions and Google sessions.
    var progress by remember(book) { mutableStateOf(FirstUseGuideProgress.decode(preferences.getString(book, null))) }
    fun persist(value: FirstUseGuideProgress) {
        preferences.edit().putString(book, value.encode()).apply()
        progress = value
    }
    LaunchedEffect(catalogLoaded, hasLoans) {
        val next = progress.assess(clientBook = true, catalogLoaded = catalogLoaded, hasLoans = hasLoans)
        if (next != progress) persist(next)
    }
    val pending = progress.pending(topic, interactive)
    var visible by remember { mutableStateOf<FirstUseTopic?>(null) }
    LaunchedEffect(pending) {
        visible = null
        if (pending != null) { delay(650); visible = pending }
    }
    val current = visible?.takeIf { it == pending } ?: return
    FirstUseGuideDialog(current, acknowledge = { persist(progress.acknowledge(current)) }, skip = { persist(progress.skipAll()) })
}

private data class GuideCopy(val icon: Int, val title: String, val body: String)
private fun guideCopy(topic: FirstUseTopic): GuideCopy = when (topic) {
    FirstUseTopic.HOME -> GuideCopy(R.drawable.ic_home, "Empieza por tu caja",
        "Toca el lápiz de Caja para registrar abonos o desembolsos, y el saldo para ver el historial. Desde Inicio, desliza a la derecha para abrir Ajustes.")
    FirstUseTopic.LOANS -> GuideCopy(R.drawable.ic_loans, "Tu primer préstamo",
        "Pulsa Nuevo préstamo para registrar un cliente y su acuerdo. Los tres puntos permiten buscar clientes y mostrar los préstamos concluidos.")
    FirstUseTopic.PAYMENTS -> GuideCopy(R.drawable.ic_payments, "Registra tus cobros",
        "Abre un cliente y elige su préstamo. Pulsa Cobrar cuota para revisar la fecha y la mora antes de registrar el pago.")
    FirstUseTopic.CREATE_LOAN -> GuideCopy(R.drawable.ic_add, "Revisa el acuerdo",
        "Por ejemplo, S/ 300 al 2 % mensual durante un mes produce S/ 6 de interés. Revisa los importes y las fechas antes de guardar.")
    FirstUseTopic.CASH -> GuideCopy(R.drawable.ic_edit, "Abonos y desembolsos",
        "Un abono aumenta tu dinero disponible; un desembolso lo reduce. Añade fecha y descripción para reconocer el movimiento después.")
    FirstUseTopic.HISTORY -> GuideCopy(R.drawable.ic_history, "Tus movimientos, en orden",
        "Aquí aparecen abonos, desembolsos, préstamos y cobros, con los más recientes primero. Usa los filtros por tipo y desliza la tabla para ver sus detalles.")
    FirstUseTopic.DETAIL -> GuideCopy(R.drawable.ic_loans, "Consulta cada préstamo",
        "La ficha reúne el acuerdo y el calendario. Desde allí puedes revisar cuotas de días anteriores y corregir sus pagos o moras.")
    FirstUseTopic.SETTINGS -> GuideCopy(R.drawable.ic_settings, "Hazla a tu medida",
        "Elige tema, color y recordatorios de cobro. Para cerrar Ajustes, toca la franja derecha o desliza el panel hacia la izquierda.")
}

@Composable private fun FirstUseGuideDialog(topic: FirstUseTopic, acknowledge: () -> Unit, skip: () -> Unit) {
    val copy = guideCopy(topic)
    AlertDialog(onDismissRequest = acknowledge,
        icon = { Icon(painterResource(copy.icon), null) },
        title = { Text(copy.title) },
        text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(copy.body)
                GuideExample(topic)
                Text("Esta ayuda aparece una sola vez al explorar esta sección.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = acknowledge) { Text("Entendido") } },
        dismissButton = { TextButton(onClick = skip) { Text("Omitir guías") } })
}

/** Native illustrations stay crisp in every theme and never create real financial records. */
@Composable private fun GuideExample(topic: FirstUseTopic) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("EJEMPLO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (topic) {
                FirstUseTopic.HOME -> {
                    ExampleAmount("Disponible en caja", "S/ 1,000.00")
                    Text("Inicio  →  Préstamos  →  Pagos", style = MaterialTheme.typography.labelMedium)
                }
                FirstUseTopic.LOANS, FirstUseTopic.DETAIL -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ClientAvatar("Ana", size = 32.dp)
                        Column { Text("Ana", fontWeight = FontWeight.SemiBold); Text("Préstamo · S/ 300.00", style = MaterialTheme.typography.bodySmall) }
                    }
                    Text("2 % mensual · 30 días", style = MaterialTheme.typography.bodySmall)
                }
                FirstUseTopic.PAYMENTS -> {
                    ExampleAmount("Cuota de hoy", "S/ 10.20")
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("Cobrar cuota", Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                FirstUseTopic.CREATE_LOAN -> {
                    ExampleAmount("Prestado", "S/ 300.00")
                    ExampleAmount("Interés", "S/ 6.00")
                    HorizontalDivider()
                    ExampleAmount("Total a recibir", "S/ 306.00")
                }
                FirstUseTopic.CASH, FirstUseTopic.HISTORY -> {
                    ExampleAmount("Abono a caja", "+ S/ 100.00")
                    ExampleAmount("Desembolso", "− S/ 40.00")
                }
                FirstUseTopic.SETTINGS -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.ic_palette), null)
                        Text("Tema y color", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.ic_notifications), null)
                        Text("Recordatorios de cobros", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable private fun ExampleAmount(label: String, amount: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(amount, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
