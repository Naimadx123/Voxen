package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired when a player asks the staff for help with `/helpop`, after the
 * cooldown check and before anything is sent or stored. Cancelling drops the
 * request; [message] may be rewritten.
 *
 * [tickets] says which way the module is set up: false means the message is
 * only broadcast to the staff, true means it opens a ticket. Watch
 * [TicketUpdateEvent] for what happens to that ticket afterwards.
 */
class HelpRequestEvent(
    val player: Player,
    var message: String,
    val tickets: Boolean,
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
