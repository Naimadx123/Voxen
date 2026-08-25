package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import zone.vao.voxen.TicketInfo

/**
 * Fired after a ticket was opened, answered or closed, from any side: the
 * player's screen, the staff commands, the web panel or the API.
 *
 * [ticket] is the state after the change. [message] carries the text that was
 * written and is null for [Action.CLOSE]. [author] is a name, so an addon can
 * tell its own doing from everyone else's and not answer itself.
 *
 * Usually fired off the server thread, on the storage worker.
 */
class TicketUpdateEvent(
    val ticket: TicketInfo,
    val action: Action,
    val author: String,
    val staff: Boolean,
    val message: String?,
) : Event(!Bukkit.isPrimaryThread()) {

    /** What happened to the ticket. */
    enum class Action {
        OPENED,
        REPLIED,
        CLOSED,
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
