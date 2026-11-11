package com.spizganed.quickbuds.ui

import android.content.Context
import android.widget.Switch
import com.spizganed.quickbuds.R

/**
 * Paints a state that comes from the earbuds, not from a tap. The first paint of a switch snaps (no slide, no fade). Every
 * later real change slides, as long as the switch is on screen: the switch moves exactly when the earbuds report a new value.
 * The same in every style. An equal value does nothing.
 */
fun Switch.setFromBuds(on: Boolean) {
    val painted = getTag(R.id.switch_painted) == true
    setTag(R.id.switch_painted, true)
    if (isChecked == on) return
    isChecked = on
    if (!painted || !isShown) jumpDrawablesToCurrentState()
}

/** The neutral state while disconnected, and the start of the next connection: snaps now, and the next paint snaps again. */
fun Switch.snapFromBuds(on: Boolean) {
    isChecked = on
    jumpDrawablesToCurrentState()
    setTag(R.id.switch_painted, null)
}

/** A switch whose tap does not toggle it: the tap becomes a request for the opposite value. */
open class RequestSwitch(context: Context) : Switch(context) {
    var onRequest: ((wantOn: Boolean) -> Unit)? = null
    override fun toggle() { val r = onRequest; if (r != null) r(!isChecked) else super.toggle() }
}
