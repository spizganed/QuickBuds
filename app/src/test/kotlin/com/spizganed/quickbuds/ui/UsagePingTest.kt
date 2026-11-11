package com.spizganed.quickbuds.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ping body and the rule that stores a reply ([UsagePing]). */
class UsagePingTest {

    private fun body(ack: Long) = JSONObject(UsagePing.pingBody("4.13.0", "install-1", ack))

    @Test fun thePingBodyCarriesTheStoredReplyIdAsAck() {
        val json = body(42L)
        assertEquals("Android", json.getString("platform"))
        assertEquals("4.13.0", json.getString("version"))
        assertEquals("install-1", json.getString("id"))
        assertEquals(42L, json.getLong("ack"))
    }

    @Test fun thePingBodySendsAckZeroWithNoStoredReply() {
        assertEquals(0L, body(0L).getLong("ack"))
    }

    /** The Worker answers with the oldest unread reply, so an older id is new to this install. */
    @Test fun anOlderReplyIsStoredWhenNoReplyWaits() {
        assertTrue(UsagePing.shouldStoreReply("The fix is in 4.14.", 48L, 50L, waiting = false))
    }

    @Test fun theStoredReplyIsNotStoredAgain() {
        assertFalse(UsagePing.shouldStoreReply("The fix is in 4.14.", 50L, 50L, waiting = false))
    }

    @Test fun aReplyIsNotStoredOverAStoredReplyThatWaits() {
        assertFalse(UsagePing.shouldStoreReply("A later answer.", 51L, 50L, waiting = true))
    }

    @Test fun aReplyIsStoredAfterTheWaitingReplyWasTaken() {
        assertTrue(UsagePing.shouldStoreReply("A later answer.", 51L, 50L, waiting = false))
    }

    @Test fun aBlankReplyOrAZeroIdIsNotStored() {
        assertFalse(UsagePing.shouldStoreReply("", 51L, 50L, waiting = false))
        assertFalse(UsagePing.shouldStoreReply("   ", 51L, 50L, waiting = false))
        assertFalse(UsagePing.shouldStoreReply("A reply.", 0L, 50L, waiting = false))
    }

    @Test fun theFirstReplyIsStored() {
        assertTrue(UsagePing.shouldStoreReply("First reply.", 7L, 0L, waiting = false))
    }
}
