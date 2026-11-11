package com.spizganed.quickbuds.bluetooth

import android.app.ForegroundServiceStartNotAllowedException
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Report 18: the service calls `startForeground` in `onCreate`. Android 15 throws
 * [ForegroundServiceStartNotAllowedException] when the start comes from the background.
 * `BudsService` catches [IllegalStateException], so this test holds that catch to the report.
 */
class ForegroundStartGuardTest {

    @Test fun theReportedRefusalIsCaughtAsIllegalStateException() {
        assertTrue(
            IllegalStateException::class.java.isAssignableFrom(
                ForegroundServiceStartNotAllowedException::class.java
            )
        )
    }
}
