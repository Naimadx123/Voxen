package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import zone.vao.voxen.NetworkPlayer

/**
 * Fired when a player arrives on the network, as opposed to walking over from
 * another server, which is [NetworkSwitchEvent] instead.
 *
 * Fires on the server the player landed on and nowhere else, so one arrival
 * means one event across the whole network. Vanished players are left out
 * while `respect-vanish` is on in `modules/system-messages.yml`.
 */
class NetworkJoinEvent(
    val player: NetworkPlayer,
) : Event(!Bukkit.isPrimaryThread()) {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
