package com.example.controlprestamos.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.controlprestamos.data.google.LoanStanding

internal data class LoanPickerColors(val background: Color, val foreground: Color, val border: Color)

/** Semantic loan colors keep their meaning across accents; selection reverses the opaque pair. */
internal fun loanPickerColors(colors: ColorScheme, selected: Boolean, standing: LoanStanding = LoanStanding.ACTIVE): LoanPickerColors {
    val dark = colors.surface.luminance() < .5f
    val (background, foreground) = if (dark) when (standing) {
        LoanStanding.CLOSED -> Color(0xFF334155) to Color(0xFFE2E8F0)
        LoanStanding.ACTIVE -> Color(0xFF153B2A) to Color(0xFF9CE8B9)
        LoanStanding.OVERDUE -> Color(0xFF4C2329) to Color(0xFFFFC2C9)
        LoanStanding.UNVERIFIED -> Color(0xFF30364A) to Color(0xFFD5DEF0)
    } else when (standing) {
        LoanStanding.CLOSED -> Color(0xFFE2E8F0) to Color(0xFF334155)
        LoanStanding.ACTIVE -> Color(0xFFDCFCE7) to Color(0xFF14532D)
        LoanStanding.OVERDUE -> Color(0xFFFEE2E2) to Color(0xFF991B1B)
        LoanStanding.UNVERIFIED -> Color(0xFFF1F5F9) to Color(0xFF475569)
    }
    return if (selected) LoanPickerColors(foreground, background, foreground)
        else LoanPickerColors(background, foreground, foreground)
}
