package com.example.controlprestamos.ui.google

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlprestamos.data.google.LoanStanding
import com.example.controlprestamos.ui.loanProgressPalette

/** The same centered label is painted with readable ink on each side of the moving fill. */
@Composable internal fun LoanProgressBar(paid: Int, total: Int, standing: LoanStanding, reveal: Float, modifier: Modifier = Modifier) {
    val palette = loanProgressPalette(MaterialTheme.colorScheme, standing)
    val text = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold)
    val label = remember(text, paid, total, style) { text.measure("$paid/$total", style) }
    val height = maxOf(22.dp, with(LocalDensity.current) { label.size.height.toDp() } + 6.dp)
    val actual = (paid.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    Canvas(modifier.height(height).clip(MaterialTheme.shapes.small).semantics {
        contentDescription = "$paid de $total cuotas pagadas"
        progressBarRangeInfo = ProgressBarRangeInfo(actual, 0f..1f)
    }) {
        drawRect(palette.track)
        val width = size.width * actual * reveal.coerceIn(0f, 1f)
        if (width > 0) drawRect(palette.fill, size = Size(width, size.height))
        val position = Offset((size.width - label.size.width) / 2f, (size.height - label.size.height) / 2f)
        drawText(label, color = palette.onTrack, topLeft = position)
        if (width > 0) clipRect(right = width) { drawText(label, color = palette.onFill, topLeft = position) }
    }
}
