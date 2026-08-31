package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import zone.vao.voxen.PartyInfo

/**
 * Fired while a party is being disbanded, whether the leader asked for it or
 * left and took it with them. Not cancellable.
 *
 * It fires before the party is taken apart, so [PartyInfo.members] still
 * lists everybody who is about to lose it. Individual members do not also get
 * a [PartyLeaveEvent].
 */
class PartyDisbandEvent(
    val party: PartyInfo,
) : Event(!Bukkit.isPrimaryThread()) {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
