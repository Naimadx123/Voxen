package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired before a piece of mail is stored, whether the mail command or
 * [zone.vao.voxen.VoxenApi.sendMail] asked for it. Cancelling drops it
 * silently and the recipient never learns it existed.
 *
 * [content] is the message after the word filter has run, and rewriting it
 * changes what is stored. The mailbox limit is checked after this fires, so a
 * full mailbox still refuses a piece a handler let through.
 *
 * Fires on whichever thread asked, which for the API path is usually not the
 * server thread, so check before you touch the Bukkit API.
 */
class MailSendEvent(
    val sender: UUID,
    val senderName: String,
    val recipient: UUID,
    var content: String,
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
