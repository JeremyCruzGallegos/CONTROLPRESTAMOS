package com.example.controlprestamos.domain

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Axis values stay in cents. Flat, empty and negative series always have a finite span. */
data class MoneyAxis(val minimum: Double, val maximum: Double, val ticks: List<Double>) {
    fun fraction(cents: Long): Float = ((cents.toDouble() - minimum) / (maximum - minimum)).toFloat().coerceIn(0f, 1f)
}
fun moneyAxis(values: List<Long>, includeZero: Boolean = false): MoneyAxis {
    var low = values.minOrNull()?.toDouble() ?: 0.0
    var high = values.maxOrNull()?.toDouble() ?: 0.0
    if (includeZero) { low = minOf(0.0, low); high = maxOf(0.0, high) }
    if (low == high) {
        val padding = maxOf(kotlin.math.abs(low) * 0.05, 100.0)
        if (includeZero && low == 0.0) high = padding else { low -= padding; high += padding }
    }
    val roughStep = (high - low) / 4
    val magnitude = 10.0.pow(floor(log10(roughStep)))
    val unit = roughStep / magnitude
    val step = (when { unit <= 1 -> 1.0; unit <= 2 -> 2.0; unit <= 5 -> 5.0; else -> 10.0 }) * magnitude
    val bottom = floor(low / step) * step
    val top = ceil(high / step) * step
    val count = ((top - bottom) / step).roundToInt()
    return MoneyAxis(bottom, top, (0..count).map { bottom + it * step })
}
fun nearestChartIndex(fraction: Float, count: Int): Int {
    require(count > 0)
    return (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt()
}
