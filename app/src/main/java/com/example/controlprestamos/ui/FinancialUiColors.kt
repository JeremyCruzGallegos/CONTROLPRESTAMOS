package com.example.controlprestamos.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.controlprestamos.data.google.LoanStanding
import com.example.controlprestamos.ui.theme.readableForeground

internal data class ActionColors(val background: Color, val foreground: Color)
internal data class CashActionPalette(val deposit: ActionColors, val withdrawal: ActionColors, val history: ActionColors)
internal data class PortfolioRowPalette(val background: Color, val foreground: Color, val secondary: Color)
internal data class LoanProgressPalette(val track: Color, val onTrack: Color, val fill: Color, val onFill: Color)

internal fun cashActionPalette(surface: Color): CashActionPalette = if (surface.luminance() < .5f)
    CashActionPalette(ActionColors(Color(0xFF183F30), Color(0xFFA3E8BC)),
        ActionColors(Color(0xFF4C2630), Color(0xFFFFC0CB)), ActionColors(Color(0xFF1E3556), Color(0xFFB4D4FF)))
else CashActionPalette(ActionColors(Color(0xFFDCFCE7), Color(0xFF14532D)),
    ActionColors(Color(0xFFFFE4E6), Color(0xFF9F1239)), ActionColors(Color(0xFFDBEAFE), Color(0xFF1E40AF)))

/** A muted completed row uses opaque, readable grays rather than fading its text. */
internal fun portfolioRowPalette(colors: ColorScheme, closed: Boolean): PortfolioRowPalette = when {
    !closed -> PortfolioRowPalette(colors.surface, colors.onSurface, colors.onSurfaceVariant)
    colors.surface.luminance() < .5f -> PortfolioRowPalette(Color(0xFF202936), Color(0xFFB5BECC), Color(0xFFAAB5C6))
    else -> PortfolioRowPalette(Color(0xFFE8EDF4), Color(0xFF52627A), Color(0xFF52627A))
}

internal fun loanProgressPalette(colors: ColorScheme, standing: LoanStanding): LoanProgressPalette {
    val track = if (standing == LoanStanding.CLOSED) {
        if (colors.surface.luminance() < .5f) Color(0xFF384150) else Color(0xFFD3DAE4)
    } else colors.surfaceContainerHighest
    val fill = if (standing == LoanStanding.CLOSED) {
        if (colors.surface.luminance() < .5f) Color(0xFF9DAABC) else Color(0xFF596A81)
    } else loanPickerColors(colors, selected = false, standing = standing).foreground
    return LoanProgressPalette(track, readableForeground(track), fill, readableForeground(fill))
}
