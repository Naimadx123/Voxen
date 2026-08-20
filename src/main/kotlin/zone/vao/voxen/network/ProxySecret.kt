package zone.vao.voxen.network

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

object ProxySecret {

    fun velocity(serverDirectory: File): String? {
        System.getenv("PAPER_VELOCITY_SECRET")?.trim()?.ifEmpty { null }?.let { return it }
        val file = File(serverDirectory, "config/paper-global.yml")
        if (!file.isFile) return null
        val yaml = runCatching { YamlConfiguration.loadConfiguration(file) }.getOrNull() ?: return null
        return yaml.getString("proxies.velocity.secret")?.trim()?.ifEmpty { null }
    }
}
