package com.spizganed.quickbuds.bluetooth

import com.spizganed.quickbuds.protocol.OpoProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The equalizer's race rules with a fake clock, a recording sender and a thread the test runs by hand. */
class EqualizerTest {
    private var clock = 0L
    private val sent = mutableListOf<String>()
    private val saved = mutableListOf<String>()
    private val jobs = ArrayDeque<() -> Unit>()

    private fun equalizer(bitmap: Set<Int> = setOf(OpoProtocol.CMD_QUERY_EQ)) = Equalizer(
        now = { clock },
        send = { _, label -> sent += label },
        supports = { it in bitmap },
        saved = null,
        save = { saved += it },
        onThread = { jobs.addLast(it) },
        pause = {},
    )

    private fun runJobs() { while (jobs.isNotEmpty()) jobs.removeFirst()() }

    @Test fun aPickHoldsAgainstOlderRepliesFor4s() {
        val eq = equalizer()
        eq.selectBuiltIn(1)
        clock = 1000
        eq.onCurrent(0)
        assertEquals(1, eq.state.current)
        clock = Equalizer.PICK_HOLD_MS + 1
        eq.onCurrent(0)
        assertEquals(0, eq.state.current)
    }

    @Test fun aQueuedPickThatANewerPickReplacedIsNotSent() {
        val eq = equalizer()
        eq.selectBuiltIn(1)
        eq.selectBuiltIn(2)
        runJobs()
        assertEquals(listOf("EQ built-in 2", "query EQ"), sent)
    }

    @Test fun theWriteGoesFirstThenOnlyTheReadsTheBudsAccept() {
        val eq = equalizer(setOf(OpoProtocol.CMD_QUERY_EQ, OpoProtocol.CMD_QUERY_BASS_BANDS))
        eq.setBassWave(3)
        runJobs()
        assertEquals(listOf("BassWave level 3", "query EQ", "query bass bands"), sent)
        assertEquals(3, eq.state.bassWave)
    }

    @Test fun theSnapshotIsSavedOnlyWithALink() {
        val eq = equalizer()
        eq.onBassLevel(2)
        assertTrue(saved.isEmpty())
        eq.onLinkUp("AA:BB:CC:DD:EE:FF")
        eq.onBassLevel(4)
        assertEquals(1, saved.size)
    }

    @Test fun oneReadSetPerLink() {
        val eq = equalizer()
        eq.refresh()
        runJobs()
        assertTrue(sent.isEmpty())
        eq.onLinkUp("AA:BB:CC:DD:EE:FF")
        eq.refresh()
        eq.refresh()
        runJobs()
        assertEquals(listOf("query EQ"), sent)
    }
}
