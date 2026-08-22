package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired before a player is muted. Cancelling calls the mute off, and both
 * [reason] and [expiresAt] may be changed before it is applied, which is how
 * you cap a duration or force a reason.
 *
 * [channelId] is null for a mute that covers every channel. [expiresAt] is a
 * timestamp in milliseconds, or null for a permanent mute.
 *
 * Fires on the server the mute was issued on; the copies that reach the rest
 * of the network are applied without firing again.
 */
class PlayerMuteEvent(
    val target: UUID,
    val targetName: String,
    val channelId: String?,
    val moderator: String,
    var reason: String?,
    var expiresAt: Long?,
) : Event(!Bukkit.isPrimaryThread()), Cancellable {

    private var cancelled = false

    /** True while [expiresAt] is null, meaning the mute never runs out. */
    fun isPermanent(): Boolean = expiresAt == null

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
