package zone.vao.voxen

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Rewrites a chat line for one viewer, so an addon can hang a badge, a
 * marker or a button off somebody else's message. Registered with
 * [VoxenApi.registerChatDecorator].
 *
 * [message] is the line as that viewer would have seen it, mention
 * highlighting included. Return the replacement, or null to leave it alone.
 * Decorators run in registration order, each one receiving what the last
 * returned.
 *
 * Runs once per viewer per message, on the chat thread rather than the
 * server thread. Permission checks are fine there; reading the world, the
 * inventory or anything else from the Bukkit API is not. Keep it quick and
 * cache on your side, because a busy channel multiplies this by every player
 * who can read it.
 *
 * Only fires for messages sent on this server. A line arriving from another
 * server over the network has no local sender and is passed through.
 */
fun interface ChatDecorator {
    fun decorate(
        sender: Player,
        viewer: Player,
        channelId: String,
        messageId: UUID,
        message: Component,
    ): Component?
}
