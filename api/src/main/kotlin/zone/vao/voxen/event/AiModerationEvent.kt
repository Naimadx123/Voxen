package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired when AI moderation has scored a message and decided what to do about
 * it, before any of it happens. Cancelling drops every action, so the message
 * stands and nobody is warned.
 *
 * [score] runs from 0 to 1 and [actions] is what the matching rule asked for.
 * Cancelling stops the rule's configured commands as well.
 *
 * Fires on the server thread, after the scoring call has come back.
 */
class AiModerationEvent(
    val player: Player,
    val content: String,
    val score: Double,
    val actions: Set<Action>,
) : Event(!Bukkit.isPrimaryThread()), Cancellable {

    private var cancelled = false

    /** What the rule asked for. */
    enum class Action {
        /** Tell everyone holding the alert permission. */
        REPORT,

        /** Delete the message. */
        DELETE,

        /** Warn the player. */
        WARN,

        /** Kick the player. */
        KICK,
    }

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
