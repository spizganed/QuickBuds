package com.spizganed.quickbuds.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The update decisions that run without Android: when to check, when to announce, what a fetch records. */
class UpdateCheckerTest {

    private val now = 1_700_000_000_000L
    private val sixHours = 6 * 60 * 60 * 1000L

    @Test fun checkRunsWhenTheSwitchIsOnAnd6HoursPassed() {
        assertTrue(UpdateChecker.shouldCheck(true, 0L, now))
        assertTrue(UpdateChecker.shouldCheck(true, now - sixHours - 1, now))
        assertTrue(UpdateChecker.shouldCheck(true, now - sixHours, now))
        assertFalse(UpdateChecker.shouldCheck(true, now - sixHours + 1, now))
        assertFalse(UpdateChecker.shouldCheck(false, 0L, now))
    }

    @Test fun announceNeedsANewerReleaseWithAnApkThatWasNotAnnounced() {
        assertTrue(UpdateChecker.shouldAnnounce("5.3.1", "v5.4.0", "https://example/QuickBuds.apk", null))
        assertFalse(UpdateChecker.shouldAnnounce("5.3.1", "v5.4.0", "https://example/QuickBuds.apk", "v5.4.0"))
        assertFalse(UpdateChecker.shouldAnnounce("5.3.1", "v5.3.1", "https://example/QuickBuds.apk", null))
        assertFalse(UpdateChecker.shouldAnnounce("5.3.1", "v5.4.0", null, null))
    }

    @Test fun failedFetchKeepsTheLastCheckTime() {
        val old = now - 7 * sixHours
        assertEquals(old, UpdateChecker.lastCheckAfterFetch(false, now, old))
        assertEquals(now, UpdateChecker.lastCheckAfterFetch(true, now, old))
    }
}
