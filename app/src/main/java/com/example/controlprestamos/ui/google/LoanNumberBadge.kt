package com.example.controlprestamos.ui.google

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlprestamos.data.google.LoanStanding
import com.example.controlprestamos.ui.loanPickerColors

@Composable internal fun LoanNumberBadge(number: Int, standing: LoanStanding, chosen: Boolean = false,
    compact: Boolean = false, click: () -> Unit) {
    val palette = loanPickerColors(MaterialTheme.colorScheme, chosen, standing)
    val badge = Modifier.sizeIn(minWidth = if (compact) 28.dp else 48.dp, minHeight = if (compact) 28.dp else 48.dp).semantics {
        contentDescription = "Préstamo $number, ${standing.label}"
        selected = chosen
    }
    val content: @Composable () -> Unit = {
        Box(Modifier.padding(horizontal = if (compact) 4.dp else 10.dp, vertical = if (compact) 3.dp else 8.dp), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = if (compact) MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp) else MaterialTheme.typography.titleSmall,
                fontWeight = if (chosen || compact) FontWeight.Bold else FontWeight.Medium)
        }
    }
    // Compact badges are part of the whole tappable table row, so their visual size need not reserve 48 dp.
    if (compact) Surface(color = palette.background, contentColor = palette.foreground, shape = MaterialTheme.shapes.small,
        border = BorderStroke(if (chosen) 2.dp else 1.dp, palette.border), modifier = badge, content = content)
    else Surface(onClick = click, color = palette.background, contentColor = palette.foreground, shape = MaterialTheme.shapes.small,
        border = BorderStroke(if (chosen) 2.dp else 1.dp, palette.border), modifier = badge, content = content)
}
