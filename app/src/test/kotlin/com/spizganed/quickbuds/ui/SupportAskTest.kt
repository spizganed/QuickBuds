package com.spizganed.quickbuds.ui

import com.spizganed.quickbuds.ui.SupportAsk.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The support cards: when one is due, and which one. */
class SupportAskTest {

    private val day = 86_400_000L
    private val now = 100 * day

    private fun pick(
        installedAt: Long = now - 20 * day, connections: Int = 0, lastDay: Long = -1,
        next: Map<Card, Long> = emptyMap(), off: Set<Card> = emptySet(),
    ) = SupportAsk.pick(installedAt, connections, now, lastDay, { next[it] ?: 0L }, { it in off })

    @Test fun nothingBeforeFourteenDaysOrTenConnections() {
        assertNull(pick(installedAt = now - 5 * day, connections = 9))
    }

    @Test fun fourteenDaysMakeTheKofiCardDue() = assertEquals(Card.KOFI, pick())

    @Test fun tenConnectionsMakeItDueOnANewInstall() =
        assertEquals(Card.KOFI, pick(installedAt = now - day, connections = 10))

    @Test fun oneCardADay() = assertNull(pick(lastDay = now / day))

    @Test fun githubFollowsKofiOnAnotherDay() {
        val next = mapOf(Card.KOFI to now + 30 * day)
        assertEquals(Card.GITHUB, pick(next = next, lastDay = now / day - 1))
    }

    @Test fun laterHoldsACardBackUntilItsDate() {
        val next = mapOf(Card.KOFI to now + day, Card.GITHUB to now + day)
        assertNull(pick(next = next))
    }

    @Test fun doNotAskAgainSkipsThatCard() = assertEquals(Card.GITHUB, pick(off = setOf(Card.KOFI)))

    @Test fun nothingWhenBothAreOff() = assertNull(pick(off = Card.values().toSet()))
}
