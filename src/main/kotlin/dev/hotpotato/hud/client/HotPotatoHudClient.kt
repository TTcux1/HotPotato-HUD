package dev.hotpotato.hud.client

import dev.hotpotato.hud.protocol.HudProtocol
import dev.hotpotato.hud.protocol.HudProtocolException
import dev.hotpotato.hud.state.HudController
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import org.slf4j.LoggerFactory

/**
 * Точка входа клиентского мода.
 *
 * Регистрация приёмника на канале `hotpotato:hud` заодно сообщает серверу,
 * что мод установлен (Fabric отправляет список каналов при входе).
 * Плагин по этому признаку отключает боссбар и шлёт данные для HUD.
 */
object HotPotatoHudClient : ClientModInitializer {

    private val logger = LoggerFactory.getLogger("hotpotato-hud")
    val hud = HudController()

    override fun onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(HudPayload.ID, HudPayload.CODEC)

        // Fabric вызывает обработчик в главном потоке клиента, синхронизация не нужна.
        ClientPlayNetworking.registerGlobalReceiver(HudPayload.ID) { payload, _ ->
            try {
                HudProtocol.decode(payload.bytes)?.let(hud::onMessage)
            } catch (e: HudProtocolException) {
                logger.warn("Повреждённое сообщение HUD: {}", e.message)
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!client.isPaused) hud.tick()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> hud.reset() }

        HudRenderCallback.EVENT.register { context, tickCounter ->
            HudRenderer.render(context, hud, tickCounter.getTickDelta(false))
        }
        logger.info("HotPotato HUD загружен")
    }
}
