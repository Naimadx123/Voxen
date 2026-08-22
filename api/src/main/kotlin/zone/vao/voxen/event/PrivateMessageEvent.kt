package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired before a private message leaves the sender, after the mute, ignore
 * and toggle checks. Cancelling drops it silently; [content] may be changed.
 *
 * [target] is null when the recipient is on another server, which is also
 * what [remote] reports; [targetName] is always set.
 *
 * Fires on the sending server only, so a network needs the handler on every
 * server to see every message.
 */
class PrivateMessageEvent(
    val sender: Player,
    val target: Player?,
    val targetName: String,
    var content: String,
    val remote: Boolean,
) : Event(!Bukkit.isPrimaryThread()), Cancellable {

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
