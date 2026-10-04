package dev.hotpotato.hud.client

import dev.hotpotato.hud.protocol.Phase
import dev.hotpotato.hud.state.FeedKind
import dev.hotpotato.hud.state.HudController
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Отрисовка HUD. Только рисование: всё состояние и тайминги живут в [HudController].
 *
 * ```
 *            ┌──────── Раунд 3 · живых 5/8 ────────┐
 *            │                 17                   │   Картошка у:
 *            │ ████████████████░░░░░░░░░░░░░░░░░░░░ │   Steve, Alex
 *            └──────────────────────────────────────┘   КЛАТЧ! Steve → Alex
 *                  У ТЕБЯ КАРТОШКА! Передай ударом       Бум! Notch
 * ```
 */
object HudRenderer {

    private const val PANEL_WIDTH = 182
    private const val BAR_HEIGHT = 5
    private const val MARGIN = 6

    private const val WHITE = 0xFFFFFF
    private const val GRAY = 0xAAAAAA
    private const val GOLD = 0xFFAA00
    private const val RED = 0xFF5555
    private const val PURPLE = 0xFF55FF
    private const val GREEN = 0x55FF55

    fun render(context: DrawContext, hud: HudController, partialTick: Float) {
        val client = MinecraftClient.getInstance()
        if (!hud.active || client.options.hudHidden) return

        val text = client.textRenderer
        val width = context.scaledWindowWidth
        val height = context.scaledWindowHeight

        if (hud.youHold && hud.phase == Phase.ROUND) drawDangerFrame(context, hud, width, height)
        drawTopPanel(context, text, hud, width, partialTick)
        drawSidebar(context, text, hud, width)
        drawBanner(context, text, hud, width, height)
    }

    // ------------------------------------------------------------------ верхняя панель

    private fun drawTopPanel(context: DrawContext, text: TextRenderer, hud: HudController, width: Int, partialTick: Float) {
        val left = (width - PANEL_WIDTH) / 2
        val top = MARGIN
        context.fill(left - 4, top - 3, left + PANEL_WIDTH + 4, top + 34, argb(0.55f, 0x000000))

        val title = when (hud.phase) {
            Phase.WAITING -> "Ожидание игроков · ${hud.players}"
            Phase.COUNTDOWN -> "Старт через ${hud.secondsLeft} с · игроков ${hud.players}"
            Phase.ROUND -> "Раунд ${hud.round} · живых ${hud.alive}/${hud.players}"
            Phase.INTERMISSION -> "Раунд ${hud.round} окончен · живых ${hud.alive}"
            Phase.FINISHED -> "Игра окончена"
        }
        context.drawCenteredTextWithShadow(text, title, width / 2, top, argb(1f, GRAY))

        if (hud.phase != Phase.ROUND) return

        // Крупный таймер: масштаб x2 через матрицу.
        val seconds = hud.secondsLeft.toString()
        val timerColor = lerpColor(WHITE, RED, hud.urgency)
        val matrices = context.matrices
        matrices.push()
        matrices.translate(width / 2f, top + 11f, 0f)
        matrices.scale(2f, 2f, 1f)
        context.drawText(text, seconds, -text.getWidth(seconds) / 2, 0, argb(1f, timerColor), true)
        matrices.pop()

        // Полоса фитиля: зелёная → жёлтая → красная, в последние секунды мигает.
        val fraction = hud.fuseFraction(partialTick)
        val barTop = top + 29
        context.fill(left, barTop, left + PANEL_WIDTH, barTop + BAR_HEIGHT, argb(0.8f, 0x333333))
        val filled = (PANEL_WIDTH * fraction).roundToInt()
        val barColor = when {
            fraction > 0.5f -> lerpColor(0xFFFF55, GREEN, (fraction - 0.5f) * 2f)
            else -> lerpColor(RED, 0xFFFF55, fraction * 2f)
        }
        val blink = hud.urgency > 0.85f && (System.currentTimeMillis() / 150) % 2 == 0L
        context.fill(left, barTop, left + filled, barTop + BAR_HEIGHT, argb(if (blink) 0.5f else 1f, barColor))

        if (hud.youHold) {
            val pulse = 0.65f + 0.35f * sin(hud.holdTicks / 3f)
            context.drawCenteredTextWithShadow(
                text, "У ТЕБЯ КАРТОШКА! Передай её ударом", width / 2, barTop + BAR_HEIGHT + 6,
                argb(pulse, RED),
            )
        }
    }

    // ------------------------------------------------------------------ правая колонка

    private fun drawSidebar(context: DrawContext, text: TextRenderer, hud: HudController, width: Int) {
        var y = MARGIN
        val right = width - MARGIN

        if (hud.phase == Phase.ROUND && hud.holders.isNotEmpty()) {
            drawRightAligned(context, text, "Картошка у:", right, y, argb(1f, GOLD))
            y += 10
            for (name in hud.holders) {
                drawRightAligned(context, text, name, right, y, argb(1f, WHITE))
                y += 10
            }
            y += 4
        }

        for (entry in hud.events) {
            if (entry.alpha < 0.05f) continue
            val color = when (entry.kind) {
                FeedKind.PASS -> GRAY
                FeedKind.CLUTCH -> PURPLE
                FeedKind.EXPLOSION -> RED
            }
            drawRightAligned(context, text, entry.text, right, y, argb(entry.alpha, color))
            y += 10
        }
    }

    // ------------------------------------------------------------------ баннер раунда

    private fun drawBanner(context: DrawContext, text: TextRenderer, hud: HudController, width: Int, height: Int) {
        val banner = hud.banner ?: return
        val alpha = banner.alpha
        if (alpha < 0.05f) return

        val centerY = height / 3f
        val matrices = context.matrices
        matrices.push()
        matrices.translate(width / 2f, centerY, 0f)
        matrices.scale(2.5f, 2.5f, 1f)
        context.drawText(text, banner.title, -text.getWidth(banner.title) / 2, 0, argb(alpha, GOLD), true)
        matrices.pop()

        context.drawCenteredTextWithShadow(text, banner.subtitle, width / 2, (centerY + 26).toInt(), argb(alpha, WHITE))
    }

    // ------------------------------------------------------------------ рамка опасности

    /** Пульсирующая красная рамка по краям экрана, быстрее к концу фитиля. */
    private fun drawDangerFrame(context: DrawContext, hud: HudController, width: Int, height: Int) {
        val speed = 4f + 8f * hud.urgency
        val alpha = 0.18f + 0.22f * ((sin(hud.holdTicks / speed) + 1f) / 2f) * (0.5f + hud.urgency / 2f)
        val color = argb(alpha, 0xFF0000)
        val thickness = 6
        context.fill(0, 0, width, thickness, color)
        context.fill(0, height - thickness, width, height, color)
        context.fill(0, thickness, thickness, height - thickness, color)
        context.fill(width - thickness, thickness, width, height - thickness, color)
    }

    // ------------------------------------------------------------------ утилиты

    private fun drawRightAligned(context: DrawContext, text: TextRenderer, value: String, right: Int, y: Int, color: Int) {
        context.drawText(text, value, right - text.getWidth(value), y, color, true)
    }

    /**
     * ARGB с прозрачностью. Альфа не опускается ниже 5/255: текст Minecraft
     * с почти нулевой альфой рисуется как непрозрачный и мигал бы при затухании.
     */
    internal fun argb(alpha: Float, rgb: Int): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt().coerceAtLeast(5)
        return (a shl 24) or (rgb and 0xFFFFFF)
    }

    internal fun lerpColor(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * k).roundToInt() and 0xFF
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
