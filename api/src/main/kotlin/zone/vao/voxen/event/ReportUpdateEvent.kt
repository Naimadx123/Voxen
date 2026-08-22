package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import zone.vao.voxen.ReportInfo

/**
 * Fired before a report is claimed, resolved, dismissed or deleted, whether
 * that came from a command, a dialog, the web panel or the API. Cancelling
 * leaves the report exactly as it is.
 *
 * [report] is the state before the change. [moderator] is a name, not a
 * player: the web panel and the console act under their own names.
 *
 * Usually fired off the server thread, on the storage worker.
 */
class ReportUpdateEvent(
    val report: ReportInfo,
    val action: ReportInfo.Action,
    val moderator: String,
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
