package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired before a report is written to the database. Cancelling drops it
 * silently, and [reason] may be rewritten before it is stored.
 *
 * The duplicate and cooldown checks have already passed by this point.
 * [messageId] is the reported chat message, or null when the report is not
 * about one.
 */
class ReportCreateEvent(
    val reporter: Player,
    val target: UUID,
    val targetName: String,
    var reason: String,
    val channelId: String?,
    val messageId: UUID?,
    val messageContent: String?,
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
