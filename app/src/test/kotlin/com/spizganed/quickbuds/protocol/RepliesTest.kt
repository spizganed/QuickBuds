package com.spizganed.quickbuds.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The known-group test has one form: the command's high byte. */
class RepliesTest {
    private val known = listOf(0x0101, 0x0205, 0x0300, 0x0400, 0x0501, 0x0F00)
    private val outside = listOf(0x0000, 0x0600, 0x0900, 0x1000)

    @Test fun knownGroupsUseTheHighByte() {
        for (cmd in known) assertTrue("0x%04X".format(cmd), Replies.knownGroup(cmd))
        for (cmd in outside) assertFalse("0x%04X".format(cmd), Replies.knownGroup(cmd))
    }

    @Test fun aKnownGroupRequestGetsNoAnswer() {
        for (cmd in known) assertNull("0x%04X".format(cmd), Replies.answerBody(cmd, ByteArray(0), 0))
        for (cmd in outside) assertEquals("0x%04X".format(cmd), listOf<Byte>(1), Replies.answerBody(cmd, ByteArray(0), 0)?.toList())
    }
}
