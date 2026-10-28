package com.example.controlprestamos.data.google

import org.junit.Assert.*
import org.junit.Test

class SheetRangesTest {
    @Test fun acceptsSingleCellReturnedForOneDayFeeRange() {
        assertTrue(sameSheetRange("'_MORA_COBRADA_2026'!KF6:KF6", "'_MORA_COBRADA_2026'!KF6"))
        assertTrue(sameSheetRange("'2026'!KF6", "'2026'!KF6:KF6"))
    }

    @Test fun acceptsQuotedAndUnquotedSheetNames() {
        assertTrue(sameSheetRange("'_CAPITAL'!A1:M8", "_CAPITAL!A1:M8"))
        assertTrue(sameSheetRange("'2026'!A6:Q205", "2026!A6:Q205"))
    }

    @Test fun comparesAbsoluteReferencesAndColumnCaseByCoordinates() {
        assertTrue(sameSheetRange("'CAJA'!\$a\$6:\$e\$910", "CAJA!A6:E910"))
    }

    @Test fun preservesEscapedApostrophesAndExclamationMarksInSheetNames() {
        assertTrue(sameSheetRange("'O''Reilly! pagos'!A1:A1", "'O''Reilly! pagos'!A1"))
        assertFalse(sameSheetRange("'O''Reilly'!A1", "OReilly!A1"))
    }

    @Test fun rejectsWrongSheetEvenWhenCellsMatch() {
        assertFalse(sameSheetRange("'2026'!A6:Q205", "'2027'!A6:Q205"))
        assertFalse(sameSheetRange("'_CAPITAL'!A1:M8", "'_capital'!A1:M8"))
    }

    @Test fun rejectsShiftedExpandedAndTruncatedRanges() {
        assertFalse(sameSheetRange("'2026'!A6:Q205", "'2026'!A7:Q206"))
        assertFalse(sameSheetRange("'2026'!A6:Q205", "'2026'!A6:R205"))
        assertFalse(sameSheetRange("'2026'!A6:Q205", "'2026'!A6:Q204"))
        assertFalse(sameSheetRange("'2026'!KF6:KF6", "'2026'!KF7"))
    }

    @Test fun rejectsMalformedOrUnboundedResponses() {
        for (range in listOf("A1", "'2026'!A:A", "'2026'!A0", "'2026'!A1:", "'2026!A1", "")) {
            assertFalse(range, sameSheetRange("'2026'!A1", range))
        }
        assertFalse(sameSheetRange("invalid", "invalid"))
    }
}
