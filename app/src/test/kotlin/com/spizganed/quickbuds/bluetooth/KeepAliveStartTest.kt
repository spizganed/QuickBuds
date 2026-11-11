package com.spizganed.quickbuds.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Bluetooth broadcast starts the service from the background. Android refuses that start with
 * [IllegalStateException] (ForegroundServiceStartNotAllowedException is a subclass), so the
 * receiver must swallow the refusal. No Android class is needed to hold this branch.
 */
class KeepAliveStartTest {

    @Test fun theStartRunsAndNoRefusalArrives() {
        var runs = 0
        var refusal: IllegalStateException? = null
        startNoThrow({ runs++ }) { refusal = it }
        assertEquals(1, runs)
        assertNull(refusal)
    }

    @Test fun aRefusedStartDoesNotThrow() {
        val denied = IllegalStateException("start not allowed")
        var refusal: IllegalStateException? = null
        startNoThrow({ throw denied }) { refusal = it }
        assertTrue(refusal === denied)
    }
}
