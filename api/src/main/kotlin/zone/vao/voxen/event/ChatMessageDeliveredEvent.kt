package zone.vao.voxen.event

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired after a chat message was delivered, with the final rendered
 * [message] and the actual [recipients]. Purely informational.
 *
 * Fires on the thread the message came from: asynchronously for regular
 * chat, synchronously when triggered by a command or the API. Check
 * [isAsynchronous] before touching the world.
 */
class ChatMessageDeliveredEvent(
    val player: Player,
    val channelId: String,
    val content: String,
    val message: Component,
    recipients: Collection<Player>,
) : Event(!Bukkit.isPrimaryThread()) {

    val recipients: Set<Player> = LinkedHashSet(recipients)

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
