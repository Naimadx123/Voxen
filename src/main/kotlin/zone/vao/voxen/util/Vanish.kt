package zone.vao.voxen.util

import org.bukkit.entity.Player

object Vanish {

    fun hidden(player: Player): Boolean =
        player.getMetadata("vanished").any { runCatching { it.asBoolean() }.getOrDefault(false) }
}
