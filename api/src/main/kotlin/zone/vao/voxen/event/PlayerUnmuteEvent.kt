package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired before a mute is lifted, and only when there is one to lift.
 * Cancelling leaves it in place.
 *
 * [channelId] names the channel being unmuted, or is null for the mute that
 * covers every channel. [allChannels] means every mute the player has is
 * going at once, which is what `/voxen unmute <player>` does.
 *
 * [targetName] is the name the mute was recorded under, so nothing has to be
 * looked up to write a log line.
 *
 * [moderator] is null when nobody is named, for instance when another plugin
 * lifted the mute through the API without saying who.
 *
 * Fires on the server the unmute was issued on.
 */
class PlayerUnmuteEvent(
    val target: UUID,
    val targetName: String,
    val channelId: String?,
    val allChannels: Boolean,
    val moderator: String?,
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
