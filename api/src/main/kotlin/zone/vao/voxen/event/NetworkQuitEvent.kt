package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired when a player leaves the network for good.
 *
 * A walk to another server does not reach this event: the quit is held back
 * for `quit.delay` in `modules/system-messages.yml`, and if the player turns
 * up elsewhere in the meantime the other server fires [NetworkSwitchEvent]
 * instead. That delay is also how long this event waits, so a leaver is
 * reported a moment after they disconnect.
 *
 * Fires on the server they left, and nowhere else.
 */
class NetworkQuitEvent(
    val uuid: UUID,
    val name: String,
    val server: String,
) : Event(!Bukkit.isPrimaryThread()) {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
