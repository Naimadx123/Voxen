package zone.vao.voxen.system

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import zone.vao.voxen.config.SystemMessagesConfig

@Suppress("UnstableApiUsage")
class SystemMessageListener(private val messages: SystemMessageService) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onJoin(event: PlayerJoinEvent) {
        if (messages.hidden(event.player) || ARRIVALS.any(::silence)) event.joinMessage(null)
        messages.join(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) {
        if (silence(SystemMessagesConfig.Kind.QUIT) || messages.hidden(event.player)) event.quitMessage(null)
        messages.quit(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) {
        val vanilla = event.deathMessage() ?: return
        if (!silence(SystemMessagesConfig.Kind.DEATH)) return
        event.deathMessage(null)
        messages.announce(
            SystemMessagesConfig.Kind.DEATH,
            event.entity,
            Placeholder.component("message", vanilla),
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val display = event.advancement.display ?: return
        if (!display.doesAnnounceToChat() || !silence(SystemMessagesConfig.Kind.ADVANCEMENT)) return
        event.message(null)
        messages.announce(
            SystemMessagesConfig.Kind.ADVANCEMENT,
            event.player,
            Placeholder.component("advancement", display.title()),
            Placeholder.component("message", display.description()),
        )
    }

    private fun silence(kind: SystemMessagesConfig.Kind): Boolean = messages.handles(kind)

    private companion object {
        val ARRIVALS = listOf(
            SystemMessagesConfig.Kind.JOIN,
            SystemMessagesConfig.Kind.FIRST_JOIN,
            SystemMessagesConfig.Kind.SERVER_SWITCH,
        )
    }
}
