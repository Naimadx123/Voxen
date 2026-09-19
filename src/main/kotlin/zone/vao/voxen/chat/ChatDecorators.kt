package zone.vao.voxen.chat

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import zone.vao.voxen.ChatDecorator
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger

class ChatDecorators(private val logger: Logger) {

    private class Entry(val id: String, val decorator: ChatDecorator)

    private val entries = CopyOnWriteArrayList<Entry>()

    fun register(id: String, decorator: ChatDecorator): Boolean {
        val name = id.lowercase()
        if (!VALID.matches(name)) return false
        synchronized(entries) {
            if (entries.any { it.id == name }) return false
            entries.add(Entry(name, decorator))
        }
        return true
    }

    fun unregister(id: String) {
        val name = id.lowercase()
        synchronized(entries) { entries.removeIf { it.id == name } }
    }

    fun apply(
        sender: Player,
        viewer: Player,
        channelId: String,
        messageId: UUID,
        message: Component,
    ): Component {
        if (entries.isEmpty()) return message
        var current = message
        for (entry in entries) {
            val next = runCatching { entry.decorator.decorate(sender, viewer, channelId, messageId, current) }
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
