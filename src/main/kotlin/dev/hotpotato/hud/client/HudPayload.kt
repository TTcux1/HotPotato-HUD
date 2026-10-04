package dev.hotpotato.hud.client

import dev.hotpotato.hud.protocol.HudProtocol
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier

/**
 * Сырые байты из канала `hotpotato:hud`. Сервер — обычный Paper-плагин,
 * он шлёт plugin message, а не пакет Fabric, поэтому кодек просто забирает
 * все байты, а разбор делает [HudProtocol], не зависящий от Minecraft.
 */
class HudPayload(val bytes: ByteArray) : CustomPayload {

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID: CustomPayload.Id<HudPayload> = CustomPayload.Id(Identifier.of(HudProtocol.CHANNEL))

        val CODEC: PacketCodec<RegistryByteBuf, HudPayload> = PacketCodec.of(
            { payload, buf -> buf.writeBytes(payload.bytes) },
            { buf ->
                val bytes = ByteArray(buf.readableBytes())
                buf.readBytes(bytes)
                HudPayload(bytes)
            },
        )
    }
}
