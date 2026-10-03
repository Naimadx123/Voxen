package zone.vao.voxen

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/**
 * Decorates local and incoming network chat once for each local viewer.
 * Registered with [VoxenApi.registerChatDecorator].
 *
 * [context] contains the body and author metadata; [message] is the complete
 * formatted line, with mention highlighting and preceding decorators.
 * Return a replacement, or null to leave the line alone. Local and network
 * decorators share one registration order. Viewer-specific decorations are
 * never published to other servers.
 *
 * The callback may run on a chat thread or the global scheduler, neither of
 * which guarantees ownership of the viewer on Folia. Keep it fast and thread
 * safe, use cached preferences, and schedule world or inventory operations
 * on the viewer's scheduler. Do not perform translation HTTP requests here.
 */
fun interface ChatMessageDecorator {
    fun decorate(context: ChatMessageContext, viewer: Player, message: Component): Component?
}
