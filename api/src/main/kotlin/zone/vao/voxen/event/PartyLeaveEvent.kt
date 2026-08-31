package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import zone.vao.voxen.PartyInfo
import java.util.UUID

/**
 * Fired after a member leaves a party or is kicked out of it. Not
 * cancellable, because by the time it fires they are already gone.
 *
 * [party] is the party as it stands after the departure, so
 * [PartyInfo.members] no longer contains [member]. A party being disbanded
 * fires [PartyDisbandEvent] once instead of one of these per member.
 */
class PartyLeaveEvent(
    val member: UUID,
    val party: PartyInfo,
    val reason: Reason,
) : Event(!Bukkit.isPrimaryThread()) {

    enum class Reason {
        LEFT,
        KICKED,
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
