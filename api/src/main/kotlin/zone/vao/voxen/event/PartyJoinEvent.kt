package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import zone.vao.voxen.PartyInfo

/**
 * Fired before a player accepts an invite and joins a party. Cancelling
 * leaves them out and burns the invite, so they have to be asked again.
 *
 * [party] is the party as it stands before the join, so [PartyInfo.members]
 * does not yet contain [player]. Creating a party does not fire this; the
 * leader is a member from the moment it exists.
 *
 * Nothing is said to the player when a handler cancels, so tell them yourself.
 */
class PartyJoinEvent(
    val player: Player,
    val party: PartyInfo,
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
