package com.spizganed.quickbuds.ui

/**
 * Decides which renders animate. A reply from the buds corrects the screen in place.
 * Only a change the user makes on this screen moves a row or the slider.
 */
class EqRenderMotion {
    private var userAction = false

    /** Call from a user-action path, before the render that follows it. */
    fun onUserAction() { userAction = true }

    /** True once after a user action. The reply that follows consumes nothing. */
    fun animate(): Boolean {
        val animate = userAction
        userAction = false
        return animate
    }
}
