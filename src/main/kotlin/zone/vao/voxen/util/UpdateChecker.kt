package zone.vao.voxen.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import zone.vao.voxen.Voxen
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

class UpdateChecker(private val plugin: Voxen) : Listener {

    @Volatile
    private var latest: String? = null

    fun start() {
        if (!plugin.configManager.config.updateChecker.enabled) return
        plugin.server.asyncScheduler.runNow(plugin) { check() }
    }

    private fun check() {
        val current = plugin.pluginMeta.version
        val found = fetch() ?: return
        if (!isNewer(found, current)) return

        latest = found
        plugin.configManager.config.messages.send(
            plugin.server.consoleSender,
            "update-available",
            Placeholder.unparsed("latest", found),
            Placeholder.unparsed("current", current),
            Placeholder.component("url", link()),
        )
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val version = latest ?: return
        val config = plugin.configManager.config
        if (!config.updateChecker.enabled || !config.updateChecker.notify) return
        if (!event.player.hasPermission("voxen.update")) return

        plugin.server.asyncScheduler.runDelayed(plugin, {
            plugin.threads.forPlayer(event.player) {
                if (!event.player.isOnline) return@forPlayer
                config.messages.send(
                    event.player,
                    "update-available",
                    Placeholder.unparsed("latest", version),
                    Placeholder.unparsed("current", plugin.pluginMeta.version),
                    Placeholder.component("url", link()),
                )
            }
        }, 2, TimeUnit.SECONDS)
    }

    private fun link() = Component.text(RELEASES)
        .decorate(TextDecoration.UNDERLINED)
        .clickEvent(ClickEvent.openUrl(RELEASES))

    private fun fetch(): String? {
        val request = HttpRequest.newBuilder(URI.create(API))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Voxen")
            .timeout(Duration.ofSeconds(10))
            .build()
        val body = runCatching {
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        }.getOrElse {
            plugin.logger.fine("Update check failed: ${it.message}")
            return null
        }
        if (body.statusCode() != 200) return null
        return TAG.find(body.body())?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    }

    companion object {
        private const val API = "https://api.github.com/repos/Naimadx123/Voxen/releases/latest"
        const val RELEASES = "https://github.com/Naimadx123/Voxen/releases"
        private val TAG = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

        fun isNewer(latest: String, current: String): Boolean {
            val a = parts(latest)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        private fun parts(version: String) = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
