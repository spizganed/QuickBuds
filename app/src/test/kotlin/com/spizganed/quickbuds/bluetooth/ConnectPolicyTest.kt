package com.spizganed.quickbuds.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automatic connects (unlock, app open, ACL fallback) start only when Android reports an audio
 * link to the buds. The user's own connect keeps the 5 try toasts; an automatic one is silent.
 */
class ConnectPolicyTest {

    @Test fun automaticConnectStartsWithTheAudioLink() {
        assertTrue(ConnectPolicy.autoConnectAllowed(LinkState.CONNECTED))
    }

    @Test fun automaticConnectDoesNotStartWithoutTheAudioLink() {
        assertFalse(ConnectPolicy.autoConnectAllowed(LinkState.DISCONNECTED))
    }

    @Test fun anUnknownLinkStateConnectsAsToday() {
        assertTrue(ConnectPolicy.autoConnectAllowed(LinkState.UNKNOWN))
    }

    @Test fun theUserConnectShowsTheTryToasts() {
        assertTrue(ConnectPolicy.showTryToast(user = true))
    }

    @Test fun anAutomaticConnectIsSilent() {
        assertFalse(ConnectPolicy.showTryToast(user = false))
    }
}
