package com.example.controlprestamos.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal data class ChartPalette(val receipts: Color, val outflows: Color, val interest: Color, val fees: Color)

/** Darker pigments on light surfaces and brighter pigments on dark surfaces keep data distinct. */
internal fun chartPalette(surface: Color): ChartPalette = if (surface.luminance() < 0.5f)
    ChartPalette(Color(0xFF2DD4BF), Color(0xFFFB923C), Color(0xFF93C5FD), Color(0xFFFBBF24))
else ChartPalette(Color(0xFF047857), Color(0xFFC2410C), Color(0xFF1D4ED8), Color(0xFFB45309))
