package com.example.controlprestamos.ui.google

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.*
import com.example.controlprestamos.ui.FinancialHero
import com.example.controlprestamos.ui.FinancialBreakdown
import com.example.controlprestamos.ui.FinancialChangeLine
import java.math.BigDecimal

@Composable internal fun CapitalEditor(summary: SheetSummary, copy: Boolean, enabled: Boolean, dismiss: () -> Unit,
    save: (CapitalChange) -> Unit) {
    // Freeze the amounts being confirmed even if another read finishes while the dialog is open.
    val before = remember { summary }
    var amount by rememberSaveable { mutableStateOf(BigDecimal.valueOf(before.initial, 2).toPlainString()) }
    var review by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cents = parseMoney(amount)
    val change = runCatching { CapitalChange(before.year, before.initial, requireNotNull(cents) { "Ingresa un importe válido con hasta dos decimales" }, before.cash) }
    val edit = change.getOrNull()
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (review) "Confirmar cambio de capital" else "Editar capital inicial") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (copy) "Libro de PRUEBAS" else "Libro OFICIAL", fontWeight = FontWeight.Bold)
            if (!review) {
                Text("Es el monto base con el que financias tus préstamos.")
                OutlinedTextField(amount, { amount = it; error = null }, label = { Text("Capital inicial · S/") },
                    singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = cents == null || (cents != before.initial && edit == null))
                if (edit == null && cents != before.initial) Text(change.exceptionOrNull()?.message ?: "Revisa el importe", color = MaterialTheme.colorScheme.error)
            }
            if (edit != null) {
                FinancialHero("Caja después del cambio", edit.cashAfter, "Vista previa del capital actualizado")
                FinancialBreakdown("Revisión del capital", "Anterior → nuevo") {
                    FinancialChangeLine("Capital inicial", soles(before.initial), soles(edit.after))
                    FinancialChangeLine("Disponible en caja", soles(before.cash), soles(edit.cashAfter))
                }
            } else Text("Capital actual: ${soles(before.initial)}")
            if (review) Text("El cambio se guardará en el teléfono, incluso sin conexión, y se enviará al libro al conectar. Se recalculará la caja de todos los años, incluidos los reportes anteriores. Los préstamos, pagos y ganancias se conservan. Se guardará el importe anterior en el historial.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        if (!review) review = true else runCatching { save(requireNotNull(edit)) }.onFailure { error = it.message ?: "No se pudo iniciar el cambio" }
    }, enabled = enabled && edit != null) { Text(if (review) "Sí, cambiar capital" else "Revisar cambio") } },
        dismissButton = { Row {
            if (review) TextButton(onClick = { review = false; error = null }) { Text("Corregir") }
            TextButton(onClick = dismiss) { Text("Cancelar") }
        } })
}
