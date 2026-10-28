package com.example.controlprestamos.ui.google

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.controlprestamos.R
import com.example.controlprestamos.data.google.SheetLoan
import com.example.controlprestamos.domain.soles
import com.example.controlprestamos.ui.FinancialLine

@Composable internal fun PaymentLoanSummary(loan: SheetLoan, count: Int, pending: Int, expanded: Boolean,
    toggle: () -> Unit, open: () -> Unit, canOpen: Boolean, content: @Composable ColumnScope.() -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), MaterialTheme.colorScheme.surface)))
                .clickable(role = Role.Button,
                onClickLabel = if (expanded) "Ocultar cuotas" else "Ver cuotas", onClick = toggle).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ClientAvatar(loan.client)
                Column(Modifier.weight(1f)) {
                    Text(loan.client, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = toggle) {
                    Icon(painterResource(R.drawable.ic_back), if (expanded) "Ocultar cuotas" else "Ver cuotas",
                        Modifier.size(20.dp).rotate(if (expanded) 90f else -90f))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LoanAmount("Prestado", loan.principal, Modifier.weight(1f))
                VerticalDivider(Modifier.height(36.dp).padding(horizontal = 12.dp))
                LoanAmount("Saldo del préstamo", loan.debt, Modifier.weight(1f), prominent = true)
            }
            FinancialLine("Ganancia esperada", soles(loan.agreed - loan.principal))
            FinancialLine("Total a recibir · capital + interés", soles(loan.agreed), emphasized = true)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Avance del préstamo", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${loan.paid}/${loan.total} pagadas", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(progress = { (loan.paid.toFloat() / loan.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$count cuotas en esta consulta", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (count > 1) StatusBadge("$pending pendientes", positive = pending == 0)
                }
                TextButton(onClick = open, enabled = canOpen, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Ver préstamo")
                }
            }
            AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider()
                    content()
                }
            }
        }
    }
}

@Composable private fun LoanAmount(label: String, amount: Long, modifier: Modifier, prominent: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(soles(amount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
