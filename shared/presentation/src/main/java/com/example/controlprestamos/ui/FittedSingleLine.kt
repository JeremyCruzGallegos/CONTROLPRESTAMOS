package com.example.controlprestamos.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/** Preserve every digit: fit the actual text to its cell instead of wrapping or abbreviating money. */
@Composable internal fun FittedSingleLine(text: String, modifier: Modifier = Modifier, style: TextStyle,
    color: Color, minimumSp: Float = 6f, textAlign: TextAlign = TextAlign.Start) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val available = with(density) { maxWidth.toPx() }
        val font = remember(text, style, available, density.fontScale) {
            val maximum = style.fontSize.value.takeIf { it.isFinite() } ?: 12f
            val first = (maximum * 4).toInt()
            val last = (minimumSp.coerceAtMost(maximum) * 4).toInt()
            (first downTo last).firstOrNull { quarter ->
                measurer.measure(text, style.copy(fontSize = (quarter / 4f).sp), maxLines = 1, softWrap = false).size.width <= available
            }?.let { (it / 4f).sp } ?: minimumSp.coerceAtMost(maximum).sp
        }
        Text(text, Modifier.fillMaxWidth(), style = style.copy(fontSize = font), color = color,
            textAlign = textAlign, maxLines = 1, softWrap = false)
    }
}
