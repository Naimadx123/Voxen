package zone.vao.voxen.presence

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import zone.vao.voxen.util.Vanish

class PresenceListener(private val presence: PresenceService) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        presence.forget(event.player.uniqueId)
        if (Vanish.hidden(event.player)) return
        presence.announceJoin(event.player.uniqueId, event.player.name)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        presence.announceQuit(event.player.uniqueId, event.player.name)
    }
}
