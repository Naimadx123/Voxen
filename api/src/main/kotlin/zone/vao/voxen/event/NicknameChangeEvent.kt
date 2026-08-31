package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired before a player's nickname changes, whatever asked for it: the nick
 * command, a moderator renaming somebody else or the API. Cancelling leaves
 * the old nickname in place.
 *
 * [from] and [nickname] are null when there was no nickname and when it is
 * being cleared. Length limits and the word filter have already passed by the
 * time this fires, so a nickname you write into [nickname] is not checked
 * again.
 *
 * Nothing is said to the player when a handler cancels, so tell them yourself.
 */
class NicknameChangeEvent(
    val player: Player,
    val from: String?,
    var nickname: String?,
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
