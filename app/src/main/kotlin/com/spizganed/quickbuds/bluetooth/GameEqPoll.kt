package com.spizganed.quickbuds.bluetooth

/** When the game-EQ poll may run. The service sleeps while the phone is locked, so the poll stops too. */
object GameEqPoll {
    fun active(connected: Boolean, gameEqOn: Boolean, usageAccess: Boolean, asleep: Boolean): Boolean =
        connected && gameEqOn && usageAccess && !asleep
}
