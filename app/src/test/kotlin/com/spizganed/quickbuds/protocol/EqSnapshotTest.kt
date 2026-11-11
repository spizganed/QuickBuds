package com.spizganed.quickbuds.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The saved EQ snapshot survives a process restart, and a reply replaces it. */
class EqSnapshotTest {

    private fun preset(id: Int, name: String, gains: List<Int>, selected: Boolean = false) =
        EqCodec.Preset(id, name, listOf(62, 250, 1000), gains, selected, byteArrayOf(-6, 6))

    /** Encodes, decodes and encodes again: the two texts must be the same. */
    private fun roundTrip(s: EqSnapshot): EqSnapshot {
        val text = s.encode()
        val back = EqSnapshot.decode(text)
        assertTrue("decode returned null for $text", back != null)
        assertEquals(text, back!!.encode())
        return back
    }

    @Test fun emptyListRoundTrip() {
        val back = roundTrip(EqSnapshot("AA:BB:CC:DD:EE:FF", 4, 2, emptyList()))
        assertEquals("AA:BB:CC:DD:EE:FF", back.mac)
        assertEquals(4, back.current)
        assertEquals(2, back.bassWave)
        assertTrue(back.custom.isEmpty())
    }

    @Test fun negativeGainsRoundTrip() {
        val p = preset(3, "Rock", listOf(-6, 0, 5), selected = true)
        val back = roundTrip(EqSnapshot("AA:BB:CC:DD:EE:FF", 3, -5, listOf(p)))
        assertEquals(1, back.custom.size)
        val q = back.custom[0]
        assertEquals(3, q.id)
        assertEquals("Rock", q.name)
        assertEquals(listOf(62, 250, 1000), q.freqs)
        assertEquals(listOf(-6, 0, 5), q.gains)
        assertTrue(q.selected)
        assertArrayEquals(byteArrayOf(-6, 6), q.tag)
    }

    @Test fun unicodeNameRoundTrip() {
        val name = "日本語 – Bas 低音 🎧"
        val back = roundTrip(EqSnapshot("AA:BB:CC:DD:EE:FF", null, null, listOf(preset(1, name, listOf(0, 0, 0)))))
        assertEquals(name, back.custom[0].name)
        assertNull(back.current)
        assertNull(back.bassWave)
    }

    @Test fun macMatchIgnoresCase() {
        val s = EqSnapshot("AA:BB:CC:DD:EE:FF", null, null, emptyList())
        assertTrue(s.matches("aa:bb:cc:dd:ee:ff"))
        assertFalse(s.matches("AA:BB:CC:DD:EE:00"))
        assertFalse(s.matches(null))
    }

    @Test fun brokenTextGivesNoSnapshot() {
        assertNull(EqSnapshot.decode(null))
        assertNull(EqSnapshot.decode(""))
        assertNull(EqSnapshot.decode("{"))
        assertNull(EqSnapshot.decode("""{"current":1}"""))
    }

    @Test fun replyWinsOverSnapshotAlsoWhenEmpty() {
        val snapshot = EqSnapshot(
            "AA:BB:CC:DD:EE:FF", 3, 2,
            listOf(preset(3, "Rock", listOf(-1, 0, 1)))
        )
        val state = EqState.forLink(snapshot, "AA:BB:CC:DD:EE:FF")
        assertEquals(1, state.custom.size)

        val empty = state.withList(emptyList())
        assertTrue(empty.custom.isEmpty())
        assertEquals(3, empty.current)
        assertEquals(2, empty.bassWave)
    }

    @Test fun replyListReplacesTheSnapshotList() {
        val snapshot = EqSnapshot("AA:BB:CC:DD:EE:FF", 3, 2, listOf(preset(3, "Rock", listOf(0, 0, 0))))
        val reply = listOf(preset(7, "Jazz", listOf(2, 2, 2)))
        val state = EqState.forLink(snapshot, "AA:BB:CC:DD:EE:FF").withList(reply)
        assertEquals(1, state.custom.size)
        assertEquals(7, state.custom[0].id)
    }

    @Test fun differentMacStartsEmpty() {
        val snapshot = EqSnapshot(
            "AA:BB:CC:DD:EE:FF", 3, 2,
            listOf(preset(3, "Rock", listOf(-1, 0, 1)))
        )
        val state = EqState.forLink(snapshot, "11:22:33:44:55:66")
        assertTrue(state.custom.isEmpty())
        assertNull(state.current)
        assertNull(state.bassWave)
    }

    @Test fun noSnapshotStartsEmpty() {
        val state = EqState.forLink(null, "AA:BB:CC:DD:EE:FF")
        assertTrue(state.custom.isEmpty())
    }

    /** After the bind the manager holds the latest state, also while the link is down. */
    @Test fun paintUsesTheManagerStateAfterTheBind() {
        val snapshot = EqSnapshot("AA:BB:CC:DD:EE:FF", 3, 2, listOf(preset(3, "Rock", listOf(0, 0, 0))))
        val manager = EqState(9, -4, listOf(preset(9, "Pop", listOf(1, 1, 1))))
        val state = EqState.paint(manager, snapshot)
        assertEquals(9, state.current)
        assertEquals(-4, state.bassWave)
        assertEquals(listOf(9), state.custom.map { it.id })
    }

    @Test fun paintUsesTheSnapshotBeforeTheBind() {
        val snapshot = EqSnapshot("AA:BB:CC:DD:EE:FF", 3, 2, listOf(preset(3, "Rock", listOf(0, 0, 0))))
        val state = EqState.paint(null, snapshot)
        assertEquals(3, state.current)
        assertEquals(2, state.bassWave)
        assertEquals(listOf(3), state.custom.map { it.id })
        assertTrue("no snapshot paints an empty state", EqState.paint(null, null).custom.isEmpty())
    }

    @Test fun beforeAnyLinkTheStateIsTheSnapshot() {
        val snap = EqSnapshot("AA:BB:CC:DD:EE:FF", 4, 2, listOf(preset(1, "Mine", listOf(1, 2, 3))))
        assertEquals(EqState(4, 2, snap.custom), EqState.beforeLink(snap))
        assertEquals(EqState(), EqState.beforeLink(null))
        assertEquals(EqState(), EqState.forLink(snap, "11:22:33:44:55:66"))
    }
}
