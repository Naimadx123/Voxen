package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired before a warning is written to a player's record, whatever asked for
 * it: a command, the moderator screens, AI moderation or the API. Cancelling
 * calls it off, and [reason] may be rewritten first.
 *
 * [moderator] is a name, not a player: the console, the web panel and an
 * addon acting for someone on another service all pass their own.
 */
class PlayerWarnEvent(
    val target: UUID,
    val targetName: String,
    var reason: String,
    val moderator: String,
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
