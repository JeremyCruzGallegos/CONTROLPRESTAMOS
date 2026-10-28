package com.example.controlprestamos.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

internal data class ChartAnimation(val progress: Float, val viewport: Modifier)

/** Observe the plot itself. Cards, axes, labels and controls stay fully visible and stationary. */
@Composable internal fun rememberChartAnimation(identity: Any, active: Boolean = true, durationMillis: Int = 1235,
    linear: Boolean = false, accelerating: Boolean = false): ChartAnimation {
    val view = LocalView.current
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    var visible by remember(identity) { mutableStateOf(false) }
    val target = visible && active
    val progress by animateFloatAsState(if (target) 1f else 0f,
        tween(durationMillis = if (target) durationMillis else 0, easing = when {
            accelerating -> CubicBezierEasing(0.55f, 0f, 0.85f, 0.45f)
            linear -> LinearEasing
            else -> FastOutSlowInEasing
        }), label = "Trazado de los datos")
    val viewport = Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = bounds.height >= minOf(threshold, coordinates.size.height * 0.25f) &&
            bounds.width >= minOf(threshold, coordinates.size.width * 0.25f) &&
            bounds.bottom > 0 && bounds.top < view.height && bounds.right > 0 && bounds.left < view.width
    }
    return ChartAnimation(progress, viewport)
}
