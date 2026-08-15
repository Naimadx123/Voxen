package zone.vao.voxen

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/**
 * Resolves a custom chat format placeholder for the message sender.
 * Registered with [VoxenApi.registerPlaceholder].
 *
 * Runs for every chat message, possibly off the main thread, so keep it
 * fast and thread safe. Return null (or throw) to render nothing; the
 * placeholder then collapses to empty text instead of breaking the message.
 */
fun interface FormatPlaceholder {
    fun resolve(player: Player): Component?
}
