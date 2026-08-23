package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired for every chat message a moderator deletes, one event per message,
 * whether it came from the delete command, the chat button, a report or the
 * API.
 *
 * [messageId] is set only when the deletion started from a report, which is
 * the one place Voxen knows which stored message was meant. [content] is the
 * text that was removed.
 *
 * Fires after the message is gone, on whichever thread deleted it.
 */
class ChatMessageDeleteEvent(
    val target: UUID,
    val targetName: String,
    val moderator: String,
    val content: String,
    val messageId: UUID?,
) : Event(!Bukkit.isPrimaryThread()) {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
