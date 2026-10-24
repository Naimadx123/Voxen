package zone.vao.voxen.event

import org.bukkit.Bukkit
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired when a message written on Discord is about to appear in a Voxen
 * channel, after the mute checks and the word and link filters. Cancelling
 * drops it silently; [content] may be changed.
 *
 * Only fires for channels that take their Discord messages over from the
 * Discord plugin, which are the ones with a `from-discord-format` (or a
 * `discord-format` to fall back on). DiscordSRV is the only plugin
 * supported so far.
 *
 * [content] is the Discord text after the filters, still in Discord markdown
 * and without attachments. [messageId] is shared with the decorator context
 * and the copies sent to the rest of the network; [discordMessageId],
 * [discordChannelId] and [authorId] are Discord snowflakes. [linkedPlayer]
 * is the author's account linked through the Discord plugin, or null.
 *
 * Fires on the server thread, on the server whose Discord plugin received
 * the message; servers that get it over the network do not fire it again.
 */
class DiscordMessageReceiveEvent(
    val channelId: String,
    val messageId: UUID,
    val discordMessageId: String,
    val discordChannelId: String,
    val authorId: String,
    val authorName: String,
    val linkedPlayer: UUID?,
    var content: String,
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
