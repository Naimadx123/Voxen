package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired before one player starts or stops ignoring another. [ignoring] is
 * true when the list is being added to and false when [target] is being taken
 * off it. Cancelling leaves the list as it was.
 *
 * One event covers both directions because a handler almost always wants to
 * see both, unlike mutes where the two ends are handled separately.
 *
 * Both sides are ids rather than players, because either of them can be
 * offline.
 */
class PlayerIgnoreEvent(
    val source: UUID,
    val target: UUID,
    val ignoring: Boolean,
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
