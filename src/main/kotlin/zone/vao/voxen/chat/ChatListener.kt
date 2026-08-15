package zone.vao.voxen.chat

import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import zone.vao.voxen.config.ChatDelivery

class ChatListener(
    private val chatService: ChatService,
    private val delivery: () -> ChatDelivery,
) : Listener {

    private val plain = PlainTextComponentSerializer.plainText()

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val raw = plain.serialize(event.message()).trim()
        if (raw.isEmpty()) {
            event.isCancelled = true
            return
        }
        if (delivery() == ChatDelivery.SYSTEM) {
            event.isCancelled = true
            chatService.chat(event.player, raw)
            return
        }

        val out = chatService.prepareChat(event.player, raw) ?: run {
            event.isCancelled = true
            return
        }
        val keep = out.recipients.mapTo(HashSet()) { it.uniqueId }
        event.viewers().removeIf { it is Player && it.uniqueId !in keep }
        event.renderer(ChatRenderer { _, _, _, viewer -> renderFor(out, viewer) })
        for (recipient in out.recipients) {
            chatService.effectsFor(out, recipient)
        }
        chatService.finish(out)
    }

    private fun renderFor(out: ChatService.Outgoing, viewer: Audience): Component =
        if (viewer is Player) chatService.viewFor(out, viewer) else chatService.consoleView(out)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        chatService.forget(event.player.uniqueId)
    }
}
