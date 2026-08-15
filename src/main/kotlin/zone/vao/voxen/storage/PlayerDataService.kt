package zone.vao.voxen.storage

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlayerDataService(
    private val plugin: JavaPlugin,
) : Listener {

    @Volatile
    private var storage: PlayerStorage? = null
    private val cache = ConcurrentHashMap<UUID, PlayerData>()
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "voxen-storage").apply { isDaemon = true }
    }

    fun attach(storage: PlayerStorage) {
        this.storage = storage
        for (player in plugin.server.onlinePlayers) {
            io.execute { cache[player.uniqueId] = load(player.uniqueId) }
        }
    }

    fun get(uuid: UUID): PlayerData = cache.getOrPut(uuid) { PlayerData.fresh(uuid) }

    fun cached(uuid: UUID): PlayerData? = cache[uuid]

    fun save(data: PlayerData) {
        io.execute { runCatching { storage?.savePlayer(data) }.onFailure(::warn) }
    }

    fun async(task: (PlayerStorage) -> Unit) {
        val current = storage ?: return
        io.execute { runCatching { task(current) }.onFailure(::warn) }
    }

    fun <T> blocking(task: (PlayerStorage) -> T): T? {
        val current = storage ?: return null
        return runCatching { task(current) }.onFailure(::warn).getOrNull()
    }

    fun shutdown() {
        for (data in cache.values) {
            runCatching { storage?.savePlayer(data) }.onFailure(::warn)
        }
        io.shutdown()
        runCatching { io.awaitTermination(5, TimeUnit.SECONDS) }
        runCatching { storage?.close() }
        storage = null
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        cache[event.uniqueId] = load(event.uniqueId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val data = cache.remove(event.player.uniqueId) ?: return
        save(data)
    }

    private fun load(uuid: UUID): PlayerData =
        runCatching { storage?.loadPlayer(uuid) }.onFailure(::warn).getOrNull() ?: PlayerData.fresh(uuid)

    private fun warn(throwable: Throwable) {
        plugin.logger.warning("Player storage operation failed: ${throwable.message}")
    }
}
