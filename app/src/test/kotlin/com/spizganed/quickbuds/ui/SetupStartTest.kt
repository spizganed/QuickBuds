package com.spizganed.quickbuds.ui

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Report 67: Android refuses a setup start on display 6. The app must keep the home screen. */
class SetupStartTest {

    @Test fun anAcceptedStartIsNotARefusal() {
        var ran = false
        val refused = attemptStart { ran = true }
        assertTrue(ran)
        assertNull(refused)
    }

    @Test fun aSecondaryDisplayRefusalDoesNotThrow() {
        val cause = SecurityException("Permission Denial: launchDisplayId=6")
        val refused = attemptStart { throw cause }
        assertSame(cause, refused)
    }
}
