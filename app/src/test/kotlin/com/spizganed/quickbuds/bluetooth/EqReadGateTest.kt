package com.spizganed.quickbuds.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One EQ read set per link: the bind call before the link does not send one. */
class EqReadGateTest {

    @Test fun noReadBeforeALink() {
        assertFalse(EqReadGate().take())
    }

    @Test fun oneReadPerLink() {
        val gate = EqReadGate()
        gate.onLinkUp()
        assertTrue("the first ask after the link sends", gate.take())
        assertFalse("a second ask in the same link is skipped", gate.take())
        gate.onLinkUp()
        assertTrue("the next link sends again", gate.take())
    }

    @Test fun downClosesTheGate() {
        val gate = EqReadGate()
        gate.onLinkUp()
        gate.take()
        gate.onLinkDown()
        assertFalse("a bind ask with no link sends nothing", gate.take())
        gate.onLinkUp()
        assertTrue("the next link sends once", gate.take())
        assertFalse(gate.take())
    }

    /** The old gate stayed open after a link that never read, so a bind before the next link sent. */
    @Test fun aLinkThatNeverReadDoesNotLeak() {
        val gate = EqReadGate()
        gate.onLinkUp()
        gate.onLinkDown()
        assertFalse("a bind ask after the link ended sends nothing", gate.take())
    }
}
