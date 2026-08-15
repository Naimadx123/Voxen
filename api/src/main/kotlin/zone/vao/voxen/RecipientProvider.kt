package zone.vao.voxen

import org.bukkit.entity.Player

/**
 * Decides who receives messages in a channel. Registered with
 * [VoxenApi.registerChannel] or [VoxenApi.registerRecipients].
 *
 * Called once per message, possibly off the main thread. Voxen still
 * applies ignores and per player chat toggles on top of the returned
 * collection; the sender is always included automatically.
 */
fun interface RecipientProvider {
    fun recipients(sender: Player): Collection<Player>
}
