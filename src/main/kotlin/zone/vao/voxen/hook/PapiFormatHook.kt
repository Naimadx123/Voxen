package zone.vao.voxen.hook

import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.entity.Player

class PapiFormatHook {

    fun apply(player: Player, text: String): String =
        runCatching { PlaceholderAPI.setPlaceholders(player, text) }.getOrDefault(text)
}
