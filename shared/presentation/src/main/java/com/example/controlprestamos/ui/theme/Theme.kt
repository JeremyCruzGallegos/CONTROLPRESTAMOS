package com.example.controlprestamos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

val Emerald = Color(0xFF10B981)
val BrandAccents = listOf(
    "Verde" to Emerald,
    "Azul" to Color(0xFF3B82F6),
    "Violeta" to Color(0xFF8B5CF6),
    "Naranja" to Color(0xFFF97316),
    "Turquesa" to Color(0xFF14B8A6),
    "Cian" to Color(0xFF06B6D4),
    "Celeste" to Color(0xFF0EA5E9),
    "Índigo" to Color(0xFF6366F1),
    "Morado" to Color(0xFFA855F7),
    "Fucsia" to Color(0xFFD946EF),
    "Rosa" to Color(0xFFEC4899),
    "Rojo" to Color(0xFFEF4444),
    "Ámbar" to Color(0xFFF59E0B),
    "Lima" to Color(0xFF84CC16),
    "Pizarra" to Color(0xFF64748B)
)

internal fun contrastRatio(first: Color, second: Color): Float {
    val a = first.luminance(); val b = second.luminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

internal fun readableForeground(background: Color): Color =
    if (contrastRatio(Color.White, background) > contrastRatio(Color.Black, background)) Color.White else Color.Black

internal fun accessibleAccent(accent: Color, background: Color): Color {
    if (contrastRatio(accent, background) >= 4.5f) return accent
    val target = if (background.luminance() < 0.5f) Color.White else Color.Black
    return (1..100).asSequence().map { lerp(accent, target, it / 100f) }
        .first { contrastRatio(it, background) >= 4.5f }
}

internal fun controlColorScheme(darkTheme: Boolean, accent: Color): ColorScheme {
    val primary = accessibleAccent(accent, if (darkTheme) Color(0xFF304058) else Color(0xFFE2E8F0))
    val container = if (darkTheme) lerp(Color(0xFF172233), accent, 0.25f) else lerp(Color.White, accent, 0.16f)
    return if (darkTheme) darkColorScheme(
        primary = primary, onPrimary = readableForeground(primary),
        primaryContainer = container, onPrimaryContainer = readableForeground(container),
        secondary = Color(0xFF9CE8CB), secondaryContainer = Color(0xFF153B36), onSecondaryContainer = Color(0xFFB7F5DC),
        tertiary = Color(0xFFFFD58B), tertiaryContainer = Color(0xFF483615), onTertiaryContainer = Color(0xFFFFD58B),
        background = Color(0xFF0E1521), onBackground = Color(0xFFF1F5FA),
        surface = Color(0xFF172233), onSurface = Color(0xFFF1F5FA),
        surfaceVariant = Color(0xFF29364B), onSurfaceVariant = Color(0xFFBBC7D8),
        outline = Color(0xFF8090A6), outlineVariant = Color(0xFF344257),
        surfaceContainer = Color(0xFF1C293D), surfaceContainerHigh = Color(0xFF26354B),
        surfaceContainerLow = Color(0xFF172233), surfaceContainerHighest = Color(0xFF304058),
        surfaceTint = accent
    ) else lightColorScheme(
        primary = primary, onPrimary = readableForeground(primary),
        primaryContainer = container, onPrimaryContainer = readableForeground(container),
        secondary = Color(0xFF19694B), secondaryContainer = Color(0xFFDCF4E8), onSecondaryContainer = Color(0xFF145A40),
        tertiary = Color(0xFF805300), tertiaryContainer = Color(0xFFFFF0D3), onTertiaryContainer = Color(0xFF805300),
        background = Color(0xFFF4F6FA), onBackground = Color(0xFF142238),
        surface = Color.White, onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFE8EDF4), onSurfaceVariant = Color(0xFF52627A),
        outline = Color(0xFF64748B), outlineVariant = Color(0xFFCBD5E1),
        surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFE2E8F0),
        surfaceContainerLow = Color.White, surfaceContainerHighest = Color(0xFFE2E8F0),
        surfaceTint = accent
    )
}

@Composable
fun ControlPrestamosTheme(darkTheme: Boolean = true, accent: Color = Emerald, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = controlColorScheme(darkTheme, accent), typography = Typography,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)), content = content)
}
