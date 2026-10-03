package zone.vao.voxen.event

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired after a chat message was delivered, with the final rendered
 * [message] and the actual [recipients]. Purely informational. [messageId]
 * matches the decorator context, chat log and outgoing network message.
 *
 * Fires on the server thread on Paper, and on the global region scheduler on
 * Folia. That is not a licence to touch the world: on Folia the global thread
 * owns no player and no chunk, so a handler that wants to teleport someone,
 * open an inventory or change a block has to go through that player's own
 * scheduler.
 *
 * Reading the event itself is always fine; everything on it is a snapshot.
 */
class ChatMessageDeliveredEvent @JvmOverloads constructor(
    val player: Player,
    val channelId: String,
    val content: String,
    val message: Component,
    recipients: Collection<Player>,
    val messageId: UUID = UUID.randomUUID(),
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
