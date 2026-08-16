package zone.vao.voxen.hook

import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

class PapiFormatHook(private val logger: Logger) {

    private val warned = AtomicBoolean()

    fun apply(player: Player, text: String): String =
        runCatching { PlaceholderAPI.setPlaceholders(player, text) }.getOrElse {
            if (warned.compareAndSet(false, true)) {
                logger.warning("PlaceholderAPI failed on \"$text\": ${it}. Later failures are not logged.")
            }
            text
        }
}
