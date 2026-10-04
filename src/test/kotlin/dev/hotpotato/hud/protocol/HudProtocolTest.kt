package dev.hotpotato.hud.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HudProtocolTest {

    @Test
    fun everyMessageSurvivesRoundTrip() {
        val messages = listOf(
            HudMessage.State(Phase.ROUND, 3, 140, 480, 5, 8, true, listOf("Steve", "Alex")),
            HudMessage.State(Phase.WAITING, 0, 0, 0, 0, 1, false, emptyList()),
            HudMessage.Passed("Steve", "Алекс", clutch = true),
            HudMessage.Exploded(listOf("Notch"), aliveLeft = 4),
            HudMessage.RoundStarted(round = 2, fuseSeconds = 27, potatoes = 2),
            HudMessage.Finished(winner = "Steve", rounds = 5),
            HudMessage.Finished(winner = null, rounds = 1),
            HudMessage.Hide,
        )
        for (message in messages) {
            assertEquals(message, HudProtocol.decode(HudProtocol.encode(message)))
        }
    }

    /**
     * Эталонные байты. Точно такая же проверка есть в плагине HotPotato:
     * если формат разойдётся, упадёт тест в одном из репозиториев.
     */
    @Test
    fun stateMatchesReferenceBytes() {
        val state = HudMessage.State(Phase.ROUND, 2, 300, 540, 3, 4, true, listOf("Steve"))
        assertEquals(REFERENCE_STATE_HEX, HudProtocol.encode(state).toHex())
        assertEquals(state, HudProtocol.decode(REFERENCE_STATE_HEX.fromHex()))
    }

    @Test
    fun passedMatchesReferenceBytes() {
        val passed = HudMessage.Passed("Steve", "Alex", clutch = true)
        assertEquals(REFERENCE_PASSED_HEX, HudProtocol.encode(passed).toHex())
    }

    @Test
    fun hideMatchesReferenceBytes() {
        assertEquals("010205", HudProtocol.encode(HudMessage.Hide).toHex())
    }

    @Test
    fun unknownVersionOrTypeIsIgnored() {
        assertNull(HudProtocol.decode(byteArrayOf(2, 1)))
        assertNull(HudProtocol.decode(byteArrayOf(1, 99)))
        assertNull(HudProtocol.decode(byteArrayOf(1, 2, 99)))
    }

    @Test
    fun truncatedMessageIsRejected() {
        val bytes = HudProtocol.encode(HudMessage.Passed("Steve", "Alex", false))
        assertThrows(HudProtocolException::class.java) {
            HudProtocol.decode(bytes.copyOf(bytes.size - 3))
        }
    }

    @Test
    fun hostileNameCountIsRejected() {
        // version, STATE, phase, 5 int, bool, затем 200 имён — столько не бывает.
        val bytes = byteArrayOf(1, 1, 2) + ByteArray(5 * 4) + byteArrayOf(0, 200.toByte())
        assertThrows(HudProtocolException::class.java) { HudProtocol.decode(bytes) }
    }

    companion object {
        const val REFERENCE_STATE_HEX =
            "010102000000020000012c0000021c000000030000000401010005537465766" + "5"
        const val REFERENCE_PASSED_HEX = "010201000553746576650004416c657801"

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
        fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
