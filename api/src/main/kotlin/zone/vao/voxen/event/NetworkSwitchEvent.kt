package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import zone.vao.voxen.NetworkPlayer

/**
 * Fired when a player moves from one server on the network to another, in
 * place of a quit on the old one and a join on the new one.
 *
 * [from] is the server they came from; [player] carries where they are now.
 * Needs `modules/presence.yml` on and a working `network.transport`; without
 * them every arrival is a plain [NetworkJoinEvent].
 *
 * Fires on the server they arrived at, and nowhere else.
 */
class NetworkSwitchEvent(
    val player: NetworkPlayer,
    val from: String,
) : Event(!Bukkit.isPrimaryThread()) {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
