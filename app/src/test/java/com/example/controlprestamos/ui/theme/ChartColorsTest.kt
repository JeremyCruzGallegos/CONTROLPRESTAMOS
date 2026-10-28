package com.example.controlprestamos.ui.theme

import com.example.controlprestamos.ui.chartPalette
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartColorsTest {
    @Test fun chartDataKeepsNonTextContrastAcrossAllThemeVariantsAndPlotSurfaces() {
        BrandAccents.forEach { (name, accent) ->
            listOf(false, true).forEach { dark ->
                val scheme = controlColorScheme(dark, accent)
                listOf(scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainerHighest).forEach { surface ->
                    val palette = chartPalette(surface)
                    listOf(palette.receipts, palette.outflows, palette.interest, palette.fees).forEach { color ->
                        assertTrue("$name dark=$dark chart contrast=${contrastRatio(color, surface)}",
                            contrastRatio(color, surface) >= 3f)
                    }
                }
            }
        }
    }
}
