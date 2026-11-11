package com.spizganed.quickbuds.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reply from the buds corrects the screen in place. Only a change the user makes animates. */
class EqRenderMotionTest {

    @Test fun aReplyNeverAnimates() {
        val motion = EqRenderMotion()
        assertFalse("the first fill after a bind does not move", motion.animate())
        assertFalse("a reply does not move", motion.animate())
    }

    @Test fun aUserChangeAnimatesOnce() {
        val motion = EqRenderMotion()
        motion.onUserAction()
        assertTrue("the user's change moves", motion.animate())
        assertFalse("the reply that follows does not move", motion.animate())
    }
}
