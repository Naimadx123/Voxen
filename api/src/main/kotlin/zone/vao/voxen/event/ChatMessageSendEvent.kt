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
 * Fires on the thread that owns the sender: the server thread on Paper, the
 * sender's own region on Folia. Handlers may read and change the sender and
 * what is around them.
 *
 * On Folia that permission stops at the sender. [recipients] belong to their
 * own regions, so touching one of them means going through their scheduler
 * first; removing them from the set is always fine.
 *
 * Keep handlers short, regular chat waits for them.
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
