package dev.hotpotato.hud.state

import dev.hotpotato.hud.protocol.HudMessage
import dev.hotpotato.hud.protocol.Phase

/**
 * Состояние HUD на клиенте. Не зависит от Minecraft: всё время задаётся
 * вызовами [tick] (20 раз в секунду), поэтому логика проверяется обычными тестами.
 *
 * Сервер присылает точное состояние раз в секунду, а между сообщениями
 * клиент сам уменьшает таймер. Так полоса фитиля движется плавно,
 * а трафика почти нет.
 */
class HudController {

    /** Есть ли что показывать: сервер с HotPotato прислал хотя бы одно состояние. */
    var active: Boolean = false
        private set

    var phase: Phase = Phase.WAITING
        private set
    var round: Int = 0
        private set
    var alive: Int = 0
        private set
    var players: Int = 0
        private set
    var youHold: Boolean = false
        private set
    var holders: List<String> = emptyList()
        private set

    private var fuseTicksLeft = 0
    private var fuseTotalTicks = 0

    /** Сколько тиков игрок держит картошку: для анимации пульсации. */
    var holdTicks: Int = 0
        private set

    private val feed = ArrayDeque<FeedEntry>()
    val events: List<FeedEntry> get() = feed.toList()

    var banner: Banner? = null
        private set

    fun onMessage(message: HudMessage) {
        when (message) {
            is HudMessage.State -> applyState(message)
            is HudMessage.Passed -> {
                val text = if (message.clutch) {
                    "КЛАТЧ! ${message.from} → ${message.to}"
                } else {
                    "${message.from} → ${message.to}"
                }
                push(text, if (message.clutch) FeedKind.CLUTCH else FeedKind.PASS)
            }
            is HudMessage.Exploded -> {
                if (message.players.isNotEmpty()) {
                    push("Бум! ${message.players.joinToString()}", FeedKind.EXPLOSION)
                }
            }
            is HudMessage.RoundStarted -> showBanner(
                "Раунд ${message.round}",
                "Фитиль ${message.fuseSeconds} с · картошек: ${message.potatoes}",
            )
            is HudMessage.Finished -> showBanner(
                if (message.winner != null) "Победитель: ${message.winner}" else "Игра окончена",
                "Раундов сыграно: ${message.rounds}",
            )
            HudMessage.Hide -> reset()
        }
    }

    fun tick() {
        if (!active) return
        if (phase == Phase.ROUND && fuseTicksLeft > 0) fuseTicksLeft--
        holdTicks = if (youHold && phase == Phase.ROUND) holdTicks + 1 else 0

        feed.forEach { it.ticksLeft-- }
        feed.removeAll { it.ticksLeft <= 0 }

        banner?.let { if (--it.ticksLeft <= 0) banner = null }
    }

    /** Выход с сервера: HUD не должен остаться висеть на другом сервере. */
    fun reset() {
        active = false
        phase = Phase.WAITING
        round = 0
        alive = 0
        players = 0
        youHold = false
        holders = emptyList()
        fuseTicksLeft = 0
        fuseTotalTicks = 0
        holdTicks = 0
        feed.clear()
        banner = null
    }

    /**
     * Доля оставшегося фитиля от 1 до 0. [partialTick] — доля между тиками
     * для плавной отрисовки на высоком FPS.
     */
    fun fuseFraction(partialTick: Float = 0f): Float {
        if (phase != Phase.ROUND || fuseTotalTicks <= 0) return 0f
        val left = (fuseTicksLeft - partialTick).coerceAtLeast(0f)
        return (left / fuseTotalTicks).coerceIn(0f, 1f)
    }

    /** Секунды до взрыва, округлённые вверх, как привыкли видеть игроки. */
    val secondsLeft: Int get() = (fuseTicksLeft + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND

    /** Насколько срочно: 0 — спокойно, 1 — последние секунды. Для цвета и пульсации. */
    val urgency: Float
        get() = when {
            phase != Phase.ROUND -> 0f
            fuseTicksLeft <= URGENT_TICKS -> 1f - fuseTicksLeft.toFloat() / URGENT_TICKS * 0.5f
            else -> (1f - fuseFraction()) * 0.5f
        }.coerceIn(0f, 1f)

    private fun applyState(state: HudMessage.State) {
        active = true
        val sameRound = phase == Phase.ROUND && state.phase == Phase.ROUND && state.round == round
        phase = state.phase
        round = state.round
        alive = state.alive
        players = state.players
        youHold = state.youHold
        holders = state.holders
        fuseTotalTicks = state.fuseTotalTicks
        // Сервер — источник истины, но не даём таймеру прыгать вверх из-за задержки пакета на тик-другой.
        fuseTicksLeft = if (sameRound && fuseTicksLeft in (state.fuseTicksLeft - 2)..<state.fuseTicksLeft) {
            fuseTicksLeft
        } else {
            state.fuseTicksLeft
        }
    }

    private fun push(text: String, kind: FeedKind) {
        feed.addFirst(FeedEntry(text, kind, FEED_TICKS))
        while (feed.size > MAX_FEED) feed.removeLast()
    }

    private fun showBanner(title: String, subtitle: String) {
        banner = Banner(title, subtitle, BANNER_TICKS)
    }

    companion object {
        const val TICKS_PER_SECOND = 20
        const val FEED_TICKS = 5 * TICKS_PER_SECOND
        const val BANNER_TICKS = 3 * TICKS_PER_SECOND
        const val MAX_FEED = 4
        const val URGENT_TICKS = 5 * TICKS_PER_SECOND
    }
}

enum class FeedKind { PASS, CLUTCH, EXPLOSION }

class FeedEntry(val text: String, val kind: FeedKind, ticksLeft: Int) {
    var ticksLeft: Int = ticksLeft
        internal set

    /** Прозрачность: запись плавно гаснет в последнюю секунду. */
    val alpha: Float get() = (ticksLeft / HudController.TICKS_PER_SECOND.toFloat()).coerceIn(0f, 1f)
}

class Banner(val title: String, val subtitle: String, ticksLeft: Int) {
    var ticksLeft: Int = ticksLeft
        internal set

    /** Появление и исчезновение по полсекунды. */
    val alpha: Float
        get() {
            val total = HudController.BANNER_TICKS
            val fade = HudController.TICKS_PER_SECOND / 2f
            val shown = total - ticksLeft
            return minOf(shown / fade, ticksLeft / fade, 1f).coerceIn(0f, 1f)
        }
}
