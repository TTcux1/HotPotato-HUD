package dev.hotpotato.hud.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * Протокол между плагином HotPotato и клиентским модом, канал `hotpotato:hud`.
 *
 * Формат бинарный, big-endian (DataOutputStream):
 * ```
 * byte version (= 1)
 * byte type    (1 = STATE, 2 = EVENT)
 * ...поля сообщения
 * ```
 * Сервер шлёт STATE раз в секунду и при смене фазы, EVENT — в момент события.
 * Клиент между сообщениями сам досчитывает таймер, поэтому трафика почти нет.
 *
 * Файл не зависит от Minecraft: тот же формат реализован в плагине,
 * совместимость проверяется тестами на одинаковых эталонных байтах.
 */
object HudProtocol {

    const val CHANNEL = "hotpotato:hud"
    const val VERSION = 1

    private const val TYPE_STATE = 1
    private const val TYPE_EVENT = 2

    private const val EVENT_PASSED = 1
    private const val EVENT_EXPLODED = 2
    private const val EVENT_ROUND_STARTED = 3
    private const val EVENT_FINISHED = 4
    private const val EVENT_HIDE = 5

    /** Защита от мусорных данных: больше этого не бывает в нормальной игре. */
    const val MAX_NAMES = 16
    private const val MAX_NAME_LENGTH = 32

    fun encode(message: HudMessage): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION)
            when (message) {
                is HudMessage.State -> {
                    out.writeByte(TYPE_STATE)
                    out.writeByte(message.phase.ordinal)
                    out.writeInt(message.round)
                    out.writeInt(message.fuseTicksLeft)
                    out.writeInt(message.fuseTotalTicks)
                    out.writeInt(message.alive)
                    out.writeInt(message.players)
                    out.writeBoolean(message.youHold)
                    writeNames(out, message.holders)
                }
                is HudMessage.Passed -> {
                    out.writeByte(TYPE_EVENT)
                    out.writeByte(EVENT_PASSED)
                    out.writeUTF(message.from)
                    out.writeUTF(message.to)
                    out.writeBoolean(message.clutch)
                }
                is HudMessage.Exploded -> {
                    out.writeByte(TYPE_EVENT)
                    out.writeByte(EVENT_EXPLODED)
                    writeNames(out, message.players)
                    out.writeInt(message.aliveLeft)
                }
                is HudMessage.RoundStarted -> {
                    out.writeByte(TYPE_EVENT)
                    out.writeByte(EVENT_ROUND_STARTED)
                    out.writeInt(message.round)
                    out.writeInt(message.fuseSeconds)
                    out.writeByte(message.potatoes)
                }
                is HudMessage.Finished -> {
                    out.writeByte(TYPE_EVENT)
                    out.writeByte(EVENT_FINISHED)
                    out.writeBoolean(message.winner != null)
                    out.writeUTF(message.winner ?: "")
                    out.writeInt(message.rounds)
                }
                HudMessage.Hide -> {
                    out.writeByte(TYPE_EVENT)
                    out.writeByte(EVENT_HIDE)
                }
            }
        }
        return bytes.toByteArray()
    }

    /**
     * Разобрать сообщение. Возвращает null для неизвестной версии или типа:
     * старый мод не должен падать, если сервер обновился раньше него.
     *
     * @throws HudProtocolException если данные повреждены
     */
    fun decode(data: ByteArray): HudMessage? {
        try {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                if (input.readUnsignedByte() != VERSION) return null
                return when (input.readUnsignedByte()) {
                    TYPE_STATE -> HudMessage.State(
                        phase = Phase.entries.getOrNull(input.readUnsignedByte()) ?: return null,
                        round = input.readInt(),
                        fuseTicksLeft = input.readInt(),
                        fuseTotalTicks = input.readInt(),
                        alive = input.readInt(),
                        players = input.readInt(),
                        youHold = input.readBoolean(),
                        holders = readNames(input),
                    )
                    TYPE_EVENT -> when (input.readUnsignedByte()) {
                        EVENT_PASSED -> HudMessage.Passed(
                            from = readName(input), to = readName(input), clutch = input.readBoolean(),
                        )
                        EVENT_EXPLODED -> HudMessage.Exploded(
                            players = readNames(input), aliveLeft = input.readInt(),
                        )
                        EVENT_ROUND_STARTED -> HudMessage.RoundStarted(
                            round = input.readInt(),
                            fuseSeconds = input.readInt(),
                            potatoes = input.readUnsignedByte(),
                        )
                        EVENT_FINISHED -> {
                            val hasWinner = input.readBoolean()
                            val winner = readName(input)
                            HudMessage.Finished(winner = winner.takeIf { hasWinner }, rounds = input.readInt())
                        }
                        EVENT_HIDE -> HudMessage.Hide
                        else -> null
                    }
                    else -> null
                }
            }
        } catch (e: EOFException) {
            throw HudProtocolException("Сообщение обрезано", e)
        } catch (e: IOException) {
            throw HudProtocolException("Не удалось прочитать сообщение", e)
        }
    }

    private fun writeNames(out: DataOutputStream, names: List<String>) {
        val limited = names.take(MAX_NAMES)
        out.writeByte(limited.size)
        limited.forEach(out::writeUTF)
    }

    private fun readNames(input: DataInputStream): List<String> {
        val count = input.readUnsignedByte()
        if (count > MAX_NAMES) throw HudProtocolException("Слишком много имён: $count")
        return List(count) { readName(input) }
    }

    private fun readName(input: DataInputStream): String {
        val name = input.readUTF()
        if (name.length > MAX_NAME_LENGTH) throw HudProtocolException("Слишком длинное имя")
        return name
    }
}

class HudProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Фазы игры. Порядок совпадает с HotPotatoGame.Phase в плагине — номер передаётся по сети. */
enum class Phase { WAITING, COUNTDOWN, ROUND, INTERMISSION, FINISHED }

sealed interface HudMessage {

    /** Снимок состояния для конкретного игрока: поле youHold у каждого своё. */
    data class State(
        val phase: Phase,
        val round: Int,
        val fuseTicksLeft: Int,
        val fuseTotalTicks: Int,
        val alive: Int,
        val players: Int,
        val youHold: Boolean,
        val holders: List<String>,
    ) : HudMessage

    data class Passed(val from: String, val to: String, val clutch: Boolean) : HudMessage
    data class Exploded(val players: List<String>, val aliveLeft: Int) : HudMessage
    data class RoundStarted(val round: Int, val fuseSeconds: Int, val potatoes: Int) : HudMessage
    data class Finished(val winner: String?, val rounds: Int) : HudMessage

    /** Игрок вышел из игры, оставаясь на сервере: HUD нужно убрать. */
    data object Hide : HudMessage
}
