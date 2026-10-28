package com.example.controlprestamos.ui

import org.junit.Assert.*
import org.junit.Test

class FirstUseGuideProgressTest {
    @Test fun loadingAnEmptyPartialCatalogDoesNotStartGuides() {
        val progress = FirstUseGuideProgress().assess(true, catalogLoaded = false, hasLoans = false)
        assertNull(progress.eligible)
        assertNull(progress.pending(FirstUseTopic.HOME, interactive = true))
        assertFalse(progress.assess(true, catalogLoaded = true, hasLoans = true).eligible!!)
    }

    @Test fun anExistingBookNeverStartsGuidesAfterItsLastLoanIsDeleted() {
        val existing = FirstUseGuideProgress().assess(true, catalogLoaded = true, hasLoans = true)
        val restarted = FirstUseGuideProgress.decode(existing.encode())
            .assess(true, catalogLoaded = true, hasLoans = false)
        assertEquals(false, restarted.eligible)
        assertNull(restarted.pending(FirstUseTopic.LOANS, interactive = true))
    }

    @Test fun onlyTheNewClientBookCanEnroll() {
        val official = FirstUseGuideProgress().assess(false, catalogLoaded = true, hasLoans = false)
        assertEquals(false, official.eligible)
        assertNull(official.pending(FirstUseTopic.HOME, interactive = true))
    }

    @Test fun firstLoanDoesNotCancelUnseenContextualGuides() {
        val started = FirstUseGuideProgress().assess(true, catalogLoaded = true, hasLoans = false)
            .acknowledge(FirstUseTopic.HOME).acknowledge(FirstUseTopic.LOANS)
        val restarted = FirstUseGuideProgress.decode(started.encode())
            .assess(true, catalogLoaded = true, hasLoans = true)
        assertEquals(true, restarted.eligible)
        assertNull(restarted.pending(FirstUseTopic.HOME, interactive = true))
        assertNull(restarted.pending(FirstUseTopic.LOANS, interactive = true))
        assertEquals(FirstUseTopic.PAYMENTS, restarted.pending(FirstUseTopic.PAYMENTS, interactive = true))
        assertEquals(FirstUseTopic.DETAIL, restarted.pending(FirstUseTopic.DETAIL, interactive = true))
    }

    @Test fun acknowledgingOneSectionDoesNotMarkOtherSectionsAsSeen() {
        var progress = FirstUseGuideProgress(eligible = true)
        for (topic in FirstUseTopic.entries) {
            assertEquals(topic, progress.pending(topic, interactive = true))
            progress = FirstUseGuideProgress.decode(progress.acknowledge(topic).encode())
            assertNull(progress.pending(topic, interactive = true))
        }
        assertEquals(FirstUseTopic.entries.toSet(), progress.seen)
    }

    @Test fun skipAllSurvivesRestartsAndFutureExploration() {
        val skipped = FirstUseGuideProgress.decode(FirstUseGuideProgress(eligible = true).skipAll().encode())
        for (topic in FirstUseTopic.entries) assertNull(skipped.pending(topic, interactive = true))
    }

    @Test fun operationsAndMissingSectionsCannotOpenAGuide() {
        val progress = FirstUseGuideProgress(eligible = true)
        assertNull(progress.pending(FirstUseTopic.CASH, interactive = false))
        assertNull(progress.pending(null, interactive = true))
        assertTrue(progress.seen.isEmpty())
        assertEquals(FirstUseTopic.CASH, progress.pending(FirstUseTopic.CASH, interactive = true))
    }

    @Test fun preferencesHandleFirstInstallUnknownTopicsAndCorruption() {
        assertEquals(FirstUseGuideProgress(), FirstUseGuideProgress.decode(null))
        assertNull(FirstUseGuideProgress.decode(FirstUseGuideProgress().encode()).eligible)
        val upgraded = FirstUseGuideProgress.decode("""{"eligible":true,"seen":["HOME","FUTURE_TOPIC"]}""")
        assertEquals(setOf(FirstUseTopic.HOME), upgraded.seen)
        assertEquals(FirstUseTopic.SETTINGS, upgraded.pending(FirstUseTopic.SETTINGS, interactive = true))
        val broken = FirstUseGuideProgress.decode("broken preference")
        assertEquals(false, broken.eligible)
        assertNull(broken.pending(FirstUseTopic.HOME, interactive = true))
    }
}
