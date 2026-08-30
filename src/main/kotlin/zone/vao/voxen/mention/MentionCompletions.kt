package zone.vao.voxen.mention

import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import zone.vao.voxen.config.MentionsConfig

class MentionCompletions(
    private val server: Server,
    private val mentions: () -> MentionsConfig,
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        if (!mentions().enabled) return
        val joined = event.player
        joined.setCustomChatCompletions(visibleTo(joined))
        val tag = listOf(tag(joined))
        for (player in server.onlinePlayers) {
            if (player.uniqueId != joined.uniqueId && player.canSee(joined)) {
                player.addCustomChatCompletions(tag)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val tag = listOf(tag(event.player))
        for (player in server.onlinePlayers) {
            if (player.uniqueId != event.player.uniqueId) player.removeCustomChatCompletions(tag)
        }
    }

    fun refresh() {
        val enabled = mentions().enabled
        for (player in server.onlinePlayers) {
            player.setCustomChatCompletions(if (enabled) visibleTo(player) else emptyList())
        }
    }

    private fun visibleTo(viewer: Player): List<String> = server.onlinePlayers
        .filter { it.uniqueId != viewer.uniqueId && viewer.canSee(it) }
        .map(::tag)

    private fun tag(player: Player): String = "@${player.name}"
}
