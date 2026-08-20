package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired before a chat message is delivered. Cancelling drops the message
 * silently; [content] and [recipients] may be modified.
 *
 * Always fires on the server thread, so handlers may read and change the
 * world freely. Keep them short; regular chat waits for them.
 */
class ChatMessageSendEvent(
    val player: Player,
    val channelId: String,
    var content: String,
    recipients: Collection<Player>,
) : Event(!Bukkit.isPrimaryThread()), Cancellable {

    val recipients: MutableSet<Player> = LinkedHashSet(recipients)

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
