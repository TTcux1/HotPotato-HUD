package dev.hotpotato.hud.state

import dev.hotpotato.hud.protocol.HudMessage
import dev.hotpotato.hud.protocol.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HudControllerTest {

    private fun state(
        phase: Phase = Phase.ROUND,
        round: Int = 1,
        left: Int = 600,
        total: Int = 600,
        youHold: Boolean = false,
    ) = HudMessage.State(phase, round, left, total, alive = 4, players = 4, youHold = youHold, holders = listOf("Steve"))

    private fun HudController.ticks(n: Int) = repeat(n) { tick() }

    @Test
    fun inactiveUntilFirstState() {
        val hud = HudController()
        assertFalse(hud.active)
        hud.onMessage(state())
        assertTrue(hud.active)
    }

    @Test
    fun timerCountsDownLocallyBetweenServerUpdates() {
        val hud = HudController()
        hud.onMessage(state(left = 600, total = 600))
        assertEquals(30, hud.secondsLeft)
        hud.ticks(10)
        assertEquals(1f - 10f / 600, hud.fuseFraction(), 1e-6f)
        hud.ticks(10)
        assertEquals(29, hud.secondsLeft)
    }

    @Test
    fun partialTickSmoothsFraction() {
        val hud = HudController()
        hud.onMessage(state(left = 100, total = 200))
        assertEquals(0.5f, hud.fuseFraction(0f), 1e-6f)
        assertEquals(99.5f / 200, hud.fuseFraction(0.5f), 1e-6f)
    }

    @Test
    fun smallLateServerUpdateDoesNotMakeTimerJumpUp() {
        val hud = HudController()
        hud.onMessage(state(left = 600))
        hud.ticks(20) // клиент: 580
        hud.onMessage(state(left = 581)) // пакет чуть запоздал
        assertEquals(580f / 600, hud.fuseFraction(), 1e-6f)
    }

    @Test
    fun largeDriftIsCorrectedByServer() {
        val hud = HudController()
        hud.onMessage(state(left = 600))
        hud.ticks(20)
        hud.onMessage(state(left = 500))
        assertEquals(500f / 600, hud.fuseFraction(), 1e-6f)
    }

    @Test
    fun newRoundResetsBar() {
        val hud = HudController()
        hud.onMessage(state(round = 1, left = 5, total = 600))
        hud.onMessage(state(round = 2, left = 540, total = 540))
        assertEquals(1f, hud.fuseFraction(), 1e-6f)
    }

    @Test
    fun urgencyRisesInLastSeconds() {
        val hud = HudController()
        hud.onMessage(state(left = 600, total = 600))
        val calm = hud.urgency
        hud.onMessage(state(left = 20, total = 600))
        assertTrue(hud.urgency > 0.85f)
        assertTrue(calm < 0.1f)
        hud.onMessage(state(phase = Phase.INTERMISSION))
        assertEquals(0f, hud.urgency)
    }

    @Test
    fun feedKeepsFourNewestAndExpires() {
        val hud = HudController()
        hud.onMessage(state())
        repeat(6) { hud.onMessage(HudMessage.Passed("A$it", "B$it", clutch = it == 5)) }
        assertEquals(4, hud.events.size)
        assertEquals("КЛАТЧ! A5 → B5", hud.events.first().text)
        assertEquals(FeedKind.CLUTCH, hud.events.first().kind)

        hud.ticks(HudController.FEED_TICKS)
        assertTrue(hud.events.isEmpty())
    }

    @Test
    fun bannerFadesInAndOut() {
        val hud = HudController()
        hud.onMessage(state())
        hud.onMessage(HudMessage.RoundStarted(round = 3, fuseSeconds = 24, potatoes = 1))
        val banner = hud.banner!!
        assertEquals("Раунд 3", banner.title)
        assertEquals(0f, banner.alpha)
        hud.ticks(HudController.TICKS_PER_SECOND)
        assertEquals(1f, banner.alpha)
        hud.ticks(HudController.BANNER_TICKS)
        assertNull(hud.banner)
    }

    @Test
    fun holdTicksCountOnlyWhileHolding() {
        val hud = HudController()
        hud.onMessage(state(youHold = true))
        hud.ticks(15)
        assertEquals(15, hud.holdTicks)
        hud.onMessage(state(youHold = false))
        hud.tick()
        assertEquals(0, hud.holdTicks)
    }

    @Test
    fun hideMessageResets() {
        val hud = HudController()
        hud.onMessage(state())
        hud.onMessage(HudMessage.Hide)
        assertFalse(hud.active)
    }

    @Test
    fun resetHidesEverything() {
        val hud = HudController()
        hud.onMessage(state())
        hud.onMessage(HudMessage.Exploded(listOf("Steve"), 3))
        hud.reset()
        assertFalse(hud.active)
        assertTrue(hud.events.isEmpty())
        assertNull(hud.banner)
    }
}
