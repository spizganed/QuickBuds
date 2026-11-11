package com.spizganed.quickbuds.bluetooth

/** The Android audio-link state of the paired buds. */
enum class LinkState { CONNECTED, DISCONNECTED, UNKNOWN }

/**
 * The two decisions of an automatic connect. No Android types, so the JVM tests cover both.
 *
 * An automatic connect (unlock, app open, ACL fallback) starts only when Android reports an audio
 * link to the buds. A disconnected link means the buds are in the closed case: they are not ready,
 * and a connect shows 5 failed tries. An unknown state connects as before.
 */
object ConnectPolicy {

    fun autoConnectAllowed(link: LinkState): Boolean = link != LinkState.DISCONNECTED

    fun showTryToast(user: Boolean): Boolean = user
}
