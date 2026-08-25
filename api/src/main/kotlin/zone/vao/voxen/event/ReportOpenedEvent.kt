package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import zone.vao.voxen.ReportInfo

/**
 * Fired once a report is in the database and has an id, which is what
 * [ReportCreateEvent] cannot give you: that one runs before the row exists so
 * it can still be vetoed, and before the duplicate check that may yet drop it.
 *
 * Listen here to build anything that has to point back at the report — a
 * Discord panel, a webhook, a ticket somewhere else — and pair it with
 * [ReportUpdateEvent] to follow what happens to it afterwards.
 *
 * Fires on the storage worker, and only on the server the report was filed
 * on, so one report means one event across the whole network.
 */
class ReportOpenedEvent(
    val report: ReportInfo,
) : Event(!Bukkit.isPrimaryThread()) {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
