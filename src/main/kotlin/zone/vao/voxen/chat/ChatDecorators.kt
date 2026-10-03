package zone.vao.voxen.chat

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import zone.vao.voxen.ChatDecorator
import zone.vao.voxen.ChatMessageContext
import zone.vao.voxen.ChatMessageDecorator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger

class ChatDecorators(private val logger: Logger) {

    private class Entry(
        val id: String,
        val decorate: (ChatMessageContext, Player?, Player, Component) -> Component?,
    )

    private val entries = CopyOnWriteArrayList<Entry>()

    fun register(id: String, decorator: ChatDecorator): Boolean =
        registerEntry(id) { context, sender, viewer, message ->
            if (context.isRemote || sender == null) null
            else decorator.decorate(sender, viewer, context.channelId, context.messageId, message)
        }

    fun register(id: String, decorator: ChatMessageDecorator): Boolean =
        registerEntry(id) { context, _, viewer, message -> decorator.decorate(context, viewer, message) }

    private fun registerEntry(
        id: String,
        decorate: (ChatMessageContext, Player?, Player, Component) -> Component?,
    ): Boolean {
        val name = id.lowercase()
        if (!VALID.matches(name)) return false
        synchronized(entries) {
            if (entries.any { it.id == name }) return false
            entries.add(Entry(name, decorate))
        }
        return true
    }

    fun unregister(id: String) {
        val name = id.lowercase()
        synchronized(entries) { entries.removeIf { it.id == name } }
    }

    fun apply(
        context: ChatMessageContext,
        sender: Player?,
        viewer: Player,
        message: Component,
    ): Component {
        if (entries.isEmpty()) return message
        var current = message
        for (entry in entries) {
            val next = runCatching { entry.decorate(context, sender, viewer, current) }
                .onFailure { logger.warning("The chat decorator '${entry.id}' failed: ${it.message}") }
                .getOrNull()
            if (next != null) current = next
        }
        return current
    }

    private companion object {
        val VALID = Regex("[a-z0-9_.-]{1,64}")
    }
}
