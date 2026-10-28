package com.example.controlprestamos.ui.theme

import org.junit.Assert.*
import org.junit.Test

class ThemeContrastTest {
    @Test fun allFifteenAccentsKeepExistingAppearancePreferencesAndAreDistinct() {
        assertEquals(15, BrandAccents.size)
        assertEquals(listOf("Verde", "Azul", "Violeta", "Naranja"), BrandAccents.take(4).map { it.first })
        assertEquals(15, BrandAccents.map { it.second }.distinct().size)
    }

    @Test fun textAndButtonPairsRemainReadableAcrossAllThirtyThemeVariants() {
        BrandAccents.forEach { (name, accent) ->
            listOf(false, true).forEach { dark ->
                val colors = controlColorScheme(dark, accent)
                val pairs = listOf(
                    "background" to (colors.onBackground to colors.background),
                    "surface" to (colors.onSurface to colors.surface),
                    "surfaceVariant" to (colors.onSurfaceVariant to colors.surfaceVariant),
                    "highSurface" to (colors.onSurface to colors.surfaceContainerHighest),
                    "primaryText" to (colors.primary to colors.surface),
                    "primaryTextHigh" to (colors.primary to colors.surfaceContainerHighest),
                    "primaryButton" to (colors.onPrimary to colors.primary),
                    "primaryContainer" to (colors.onPrimaryContainer to colors.primaryContainer),
                    "secondary" to (colors.onSecondaryContainer to colors.secondaryContainer),
                    "tertiary" to (colors.onTertiaryContainer to colors.tertiaryContainer),
                    "error" to (colors.onErrorContainer to colors.errorContainer))
                pairs.forEach { (role, pair) ->
                    val ratio = contrastRatio(pair.first, pair.second)
                    assertTrue("$name dark=$dark $role contrast=$ratio", ratio >= 4.5f)
                }
            }
        }
    }

    @Test fun loanNumbersAndSelectedStateRemainDistinctAcrossAllAccentsAndBothThemes() {
        BrandAccents.forEach { (name, accent) ->
            listOf(false, true).forEach { dark ->
                val colors = controlColorScheme(dark, accent)
                val chosen = com.example.controlprestamos.ui.loanPickerColors(colors, true)
                val other = com.example.controlprestamos.ui.loanPickerColors(colors, false)
                for (palette in listOf(chosen, other)) {
                    assertTrue("$name dark=$dark number", contrastRatio(palette.foreground, palette.background) >= 4.5f)
                }
                assertTrue("$name dark=$dark selection", contrastRatio(chosen.background, other.background) >= 3f)
                assertTrue("$name dark=$dark outline", contrastRatio(other.border, other.background) >= 3f)
            }
        }
    }

    @Test fun semanticLoanStatesStayReadableAndSelectionDistinctInBothThemesAndEveryAccent() {
        BrandAccents.forEach { (name, accent) -> listOf(false, true).forEach { dark ->
            val colors = controlColorScheme(dark, accent)
            com.example.controlprestamos.data.google.LoanStanding.entries.forEach { state ->
                val chosen = com.example.controlprestamos.ui.loanPickerColors(colors, true, state)
                val other = com.example.controlprestamos.ui.loanPickerColors(colors, false, state)
                assertTrue("$name dark=$dark $state selected", contrastRatio(chosen.foreground, chosen.background) >= 4.5f)
                assertTrue("$name dark=$dark $state normal", contrastRatio(other.foreground, other.background) >= 4.5f)
                assertTrue("$name dark=$dark $state selection", contrastRatio(chosen.background, other.background) >= 3f)
                assertTrue("$name dark=$dark $state border", contrastRatio(other.border, other.background) >= 3f)
            }
        } }
    }

    @Test fun cashActionsHaveThreeDistinctOpaqueColorsAndReadableIconsAcrossAllThemeVariants() {
        BrandAccents.forEach { (name, accent) -> listOf(false, true).forEach { dark ->
            val colors = controlColorScheme(dark, accent)
            val palette = com.example.controlprestamos.ui.cashActionPalette(colors.surface)
            val actions = listOf(palette.deposit, palette.withdrawal, palette.history)
            assertEquals("$name dark=$dark distinct cash actions", 3, actions.map { it.background }.distinct().size)
            actions.forEach { action ->
                assertEquals(1f, action.background.alpha, 0f)
                assertTrue("$name dark=$dark cash contrast", contrastRatio(action.foreground, action.background) >= 4.5f)
            }
        } }
    }
    @Test fun completedRowGraysStayReadableWithoutUsingOpacityToMuteTheirText() {
        BrandAccents.forEach { (name, accent) -> listOf(false, true).forEach { dark ->
            val colors = controlColorScheme(dark, accent)
            val row = com.example.controlprestamos.ui.portfolioRowPalette(colors, true)
            assertEquals(1f, row.background.alpha, 0f)
            assertTrue("$name dark=$dark completed amount", contrastRatio(row.foreground, row.background) >= 4.5f)
            assertTrue("$name dark=$dark completed date", contrastRatio(row.secondary, row.background) >= 4.5f)
            assertNotEquals(colors.surface, row.background)
            assertTrue(contrastRatio(row.foreground, row.background) < contrastRatio(colors.onSurface, colors.surface))
        } }
    }
    @Test fun progressCounterInkIsReadableOverBothTheTrackAndFilledPartsInEveryLoanState() {
        BrandAccents.forEach { (name, accent) -> listOf(false, true).forEach { dark ->
            val colors = controlColorScheme(dark, accent)
            com.example.controlprestamos.data.google.LoanStanding.entries.forEach { state ->
                val progress = com.example.controlprestamos.ui.loanProgressPalette(colors, state)
                assertTrue("$name dark=$dark $state unfilled text", contrastRatio(progress.onTrack, progress.track) >= 4.5f)
                assertTrue("$name dark=$dark $state filled text", contrastRatio(progress.onFill, progress.fill) >= 4.5f)
                assertTrue("$name dark=$dark $state progress visibility", contrastRatio(progress.fill, progress.track) >= 3f)
            }
        } }
    }

    @Test fun cashMenuSymbolsRemainReadableOnThePopupSurfaceAcrossAllThemeVariants() {
        BrandAccents.forEach { (name, accent) -> listOf(false, true).forEach { dark ->
            val colors = controlColorScheme(dark, accent)
            val palette = com.example.controlprestamos.ui.cashActionPalette(colors.surface)
            listOf(palette.deposit, palette.withdrawal).forEach { action ->
                assertTrue("$name dark=$dark cash popup symbol", contrastRatio(action.foreground, colors.surfaceContainer) >= 4.5f)
            }
        } }
    }

    @Test fun selectedSwatchesUseReadableCheckmarksEvenForYellowOrDarkColors() {
        BrandAccents.forEach { (name, color) ->
            assertTrue(name, contrastRatio(readableForeground(color), color) >= 4.5f)
        }
    }
}
