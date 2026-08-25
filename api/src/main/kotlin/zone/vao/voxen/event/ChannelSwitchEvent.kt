package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired before a player's active channel changes, whatever asked for it: the
 * channel command, a quick chat prefix or the API. Cancelling leaves the
 * player where they were.
 *
 * [from] is null when they had no active channel yet. Switching also joins
 * the channel, so a cancelled switch leaves the membership alone as well.
 */
class ChannelSwitchEvent(
    val player: Player,
    val from: String?,
    val to: String,
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
