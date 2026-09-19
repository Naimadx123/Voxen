package zone.vao.voxen.ignore

import org.bukkit.Server
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import zone.vao.voxen.event.PlayerIgnoreEvent
import zone.vao.voxen.storage.PlayerDataService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class IgnoreService(
    private val server: Server,
    private val playerData: PlayerDataService,
) : Listener {

    private val ignores = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    fun isIgnoring(source: UUID, target: UUID): Boolean =
        ignores[source]?.contains(target) == true

    fun ignored(source: UUID): Set<UUID> = ignores[source]?.toSet().orEmpty()

    fun ignore(source: UUID, target: UUID): Boolean {
        if (source == target) return false
        if (isIgnoring(source, target)) return false
        if (!announce(source, target, true)) return false
        val added = ignores.getOrPut(source) { ConcurrentHashMap.newKeySet() }.add(target)
        if (added) playerData.async { it.addIgnore(source, target) }
        return added
    }

    fun unignore(source: UUID, target: UUID): Boolean {
        if (!isIgnoring(source, target)) return false
        if (!announce(source, target, false)) return false
        val removed = ignores[source]?.remove(target) == true
        if (removed) playerData.async { it.removeIgnore(source, target) }
        return removed
    }

    private fun announce(source: UUID, target: UUID, ignoring: Boolean): Boolean {
        val event = PlayerIgnoreEvent(source, target, ignoring)
        server.pluginManager.callEvent(event)
        return !event.isCancelled
    }

    fun loadOnline(onlineIds: Collection<UUID>) {
        for (uuid in onlineIds) {
            playerData.async { storage -> ignores[uuid] = ConcurrentHashMap.newKeySet<UUID>().apply { addAll(storage.loadIgnores(uuid)) } }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        val loaded = playerData.blocking { it.loadIgnores(event.uniqueId) }.orEmpty()
        ignores[event.uniqueId] = ConcurrentHashMap.newKeySet<UUID>().apply { addAll(loaded) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        ignores.remove(event.player.uniqueId)
    }
}
