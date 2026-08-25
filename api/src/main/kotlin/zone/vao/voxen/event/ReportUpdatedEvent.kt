package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import zone.vao.voxen.ReportInfo

/**
 * Fired once a report has actually been claimed, resolved, dismissed or
 * deleted, which is what [ReportUpdateEvent] cannot tell you: that one runs
 * before the write so it can still be vetoed, and carries the state as it was.
 *
 * [report] is the state **after** the change, so anything mirroring a report
 * elsewhere can redraw straight from it. For [ReportInfo.Action.DELETE] it is
 * the last state the report had before it was removed.
 *
 * [moderator] is the name the change was made under, so an addon can skip its
 * own doing. Fires on the storage worker.
 */
class ReportUpdatedEvent(
    val report: ReportInfo,
    val action: ReportInfo.Action,
    val moderator: String,
) : Event(!Bukkit.isPrimaryThread()) {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
