package com.spizganed.quickbuds.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The game-EQ poll runs only with a live link, the switch on, usage access, and a phone that is not locked. */
class GameEqPollTest {

    @Test fun aLockedPhoneNeverPolls() {
        assertFalse(
            "the poll must not start while the service sleeps",
            GameEqPoll.active(connected = true, gameEqOn = true, usageAccess = true, asleep = true)
        )
    }

    @Test fun pollsWhenAwakeAndLinked() {
        assertTrue(GameEqPoll.active(connected = true, gameEqOn = true, usageAccess = true, asleep = false))
    }

    @Test fun noPollWithoutTheLink() {
        assertFalse(GameEqPoll.active(connected = false, gameEqOn = true, usageAccess = true, asleep = false))
    }

    @Test fun noPollWithTheSwitchOff() {
        assertFalse(GameEqPoll.active(connected = true, gameEqOn = false, usageAccess = true, asleep = false))
    }

    @Test fun noPollWithoutUsageAccess() {
        assertFalse(GameEqPoll.active(connected = true, gameEqOn = true, usageAccess = false, asleep = false))
    }
}
