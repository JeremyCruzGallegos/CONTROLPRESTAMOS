package com.example.controlprestamos.ui.google

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.data.google.*
import com.example.controlprestamos.domain.*
import com.example.controlprestamos.ui.FinancialLine

@Composable internal fun AmountPaymentDialog(loan: SheetLoan, calendar: LoanCalendar, dismiss: () -> Unit,
    apply: (Long,Boolean) -> Unit) {
    var amount by rememberSaveable(loan.id) { mutableStateOf("") }
    var waive by rememberSaveable(loan.id) { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val cents=parseMoney(amount)
    val preview=remember(loan,calendar,cents,waive) { runCatching {
        planAmountPayment(recordForAmount(loan,calendar,today()),cents?:error("Ingresa el monto"),today(),waive)
    } }
    AlertDialog(onDismissRequest=dismiss,title={Text("Pago múltiple")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(loan.client)
            Text("Se aplica a las cuotas vencidas más antiguas. El sobrante reduce la mora y después la base de la siguiente cuota pendiente.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(amount,{amount=it;saveError=null},label={Text("Dinero recibido · S/")},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(waive,{waive=it;saveError=null});Text("Sin mora en este pago")
            }
            preview.getOrNull()?.let { plan->
                Text("${plan.paidDates.size} cuotas quedarán pagadas")
                Text("Del ${dateLabel(plan.paidDates.first())} al ${dateLabel(plan.paidDates.last())}",style=MaterialTheme.typography.bodySmall)
                plan.partialDate?.let { due->
                    FinancialLine("Abono al ${dateLabel(due)}",soles(plan.partialAmount))
                    val after=plan.after.view(today())
                    FinancialLine("Resta en esa cuota",soles(after.baseOn(due)+after.lateAt(due,today())),true)
                }
                FinancialLine("Saldo del préstamo después",soles(plan.after.view(today()).debt),true)
            }
            if(amount.isNotBlank())preview.exceptionOrNull()?.message?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            saveError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={TextButton(onClick={runCatching {apply(requireNotNull(cents),waive)}.onFailure { saveError=it.message }},
        enabled=preview.isSuccess){Text("Registrar pago")}},dismissButton={TextButton(onClick=dismiss){Text("Cancelar")}})
}
