package com.spizganed.quickbuds.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The OnePlus link dialog asks once per install, and only on a OnePlus, OPPO or realme phone. */
class KnownIssuesLinkTest {

    @Test fun oneplusPhoneBeforeShowAsks() {
        assertTrue(KnownIssues.shouldAnnounceLink("OnePlus", shownBefore = false))
        assertTrue(KnownIssues.shouldAnnounceLink("OPPO", shownBefore = false))
        assertTrue(KnownIssues.shouldAnnounceLink("realme", shownBefore = false))
    }

    @Test fun phoneAfterShowDoesNotAsk() {
        assertFalse(KnownIssues.shouldAnnounceLink("OnePlus", shownBefore = true))
        assertFalse(KnownIssues.shouldAnnounceLink("OPPO", shownBefore = true))
        assertFalse(KnownIssues.shouldAnnounceLink("realme", shownBefore = true))
    }

    @Test fun otherPhoneDoesNotAsk() {
        assertFalse(KnownIssues.shouldAnnounceLink("Google", shownBefore = false))
        assertFalse(KnownIssues.shouldAnnounceLink("Nothing", shownBefore = false))
        assertFalse(KnownIssues.shouldAnnounceLink("samsung", shownBefore = false))
        assertFalse(KnownIssues.shouldAnnounceLink("", shownBefore = false))
        assertFalse(KnownIssues.shouldAnnounceLink(null, shownBefore = false))
    }
}
