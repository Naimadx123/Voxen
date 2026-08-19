package zone.vao.voxen.chat

import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import zone.vao.voxen.config.ChatDelivery
import zone.vao.voxen.moderation.AiModerationService
import zone.vao.voxen.moderation.ModeratorService

class ChatListener(
    private val chatService: ChatService,
    private val delivery: () -> ChatDelivery,
    private val moderator: ModeratorService,
    private val ai: AiModerationService,
) : Listener {

    private val plain = PlainTextComponentSerializer.plainText()

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val raw = plain.serialize(event.message()).trim()
        if (raw.isEmpty()) {
            event.isCancelled = true
            return
        }
        ai.inspect(event.player, raw)
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
        val signed = runCatching { event.signedMessage() }.getOrNull()
        event.renderer(ChatRenderer { _, _, _, viewer -> renderFor(out, viewer, event.player, signed) })
        for (recipient in out.recipients) {
            chatService.effectsFor(out, recipient)
        }
        chatService.finish(out)
        if (signed != null) moderator.remember(event.player, signed)
    }

    private fun renderFor(
        out: ChatService.Outgoing,
        viewer: Audience,
        sender: Player,
        signed: SignedMessage?,
    ): Component {
        if (viewer !is Player) return chatService.consoleView(out)
        val view = chatService.viewFor(out, viewer)
        val buttons = signed?.let { moderator.chatButtons(sender, it, viewer) } ?: return view
        return Component.empty().append(buttons).append(view)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        chatService.forget(event.player.uniqueId)
    }
}
