package zone.vao.voxen.storage

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import zone.vao.voxen.util.WorkQueue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PlayerDataService(
    private val plugin: JavaPlugin,
    private val queueCapacity: Int = 500,
    private val chatLogBatch: Int = 100,
) : Listener {

    @Volatile
    private var storage: PlayerStorage? = null
    private val cache = ConcurrentHashMap<UUID, PlayerData>()
    private val dirty = ConcurrentHashMap<UUID, PlayerData>()
    private val chatLog = ConcurrentLinkedQueue<ChatLogEntry>()
    private val chatLogSize = AtomicInteger()
    private val flushQueued = AtomicBoolean(false)
    private val io = WorkQueue(
        "voxen-storage",
        queueCapacity,
        plugin.logger,
        "The database is unreachable or cannot keep up, so some writes are lost.",
    )

    fun attach(storage: PlayerStorage) {
        this.storage = storage
        for (player in plugin.server.onlinePlayers) {
            io.submit { cache[player.uniqueId] = load(player.uniqueId) }
        }
    }

    fun get(uuid: UUID): PlayerData = cache.getOrPut(uuid) { PlayerData.fresh(uuid) }

    fun cached(uuid: UUID): PlayerData? = cache[uuid]

    fun save(data: PlayerData) {
        dirty[data.uuid] = data
        scheduleFlush()
    }

    fun logChat(entry: ChatLogEntry) {
        if (chatLogSize.get() >= queueCapacity) {
            io.noteDrop()
            return
        }
        chatLog += entry
        chatLogSize.incrementAndGet()
        scheduleFlush()
    }

    fun async(task: (PlayerStorage) -> Unit): Boolean {
        val current = storage ?: return false
        return io.submit { runCatching { task(current) }.onFailure(::warn) }
    }

    /** Durable write with no caller to notify: a rejected op is logged loudly instead of vanishing. */
    fun durable(label: String, task: (PlayerStorage) -> Unit) {
        durable(task) { plugin.logger.severe("$label was not persisted: the storage queue is full or failing.") }
    }

    /** Like [async], but a write that cannot be queued or fails calls [onReject] instead of vanishing. */
    fun durable(task: (PlayerStorage) -> Unit, onReject: () -> Unit) {
        val current = storage ?: run {
            onReject()
            return
        }
        val queued = io.submit {
            runCatching { task(current) }.onFailure { failure ->
                warn(failure)
                onReject()
            }
        }
        if (!queued) onReject()
    }

    fun <T> blocking(task: (PlayerStorage) -> T): T? {
        val current = storage ?: return null
        return runCatching { task(current) }.onFailure(::warn).getOrNull()
    }

    fun shutdown() {
        // drain queued work first, otherwise the pool writes through a closed data source
        io.shutdown(5)
        for (data in cache.values) dirty[data.uuid] = data
        cache.clear()
        flushPlayers()
        flushChatLog()
        runCatching { storage?.close() }
        storage = null
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        val data = load(event.uniqueId)
        cache[event.uniqueId] = data
        // the name is what cross-server lookups (mail, mutes) search by, so keep it current
        if (!data.lastName.equals(event.name, ignoreCase = true)) {
            data.lastName = event.name
            save(data)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val data = cache.remove(event.player.uniqueId) ?: return
        save(data)
    }

    private fun scheduleFlush() {
        if (!flushQueued.compareAndSet(false, true)) return
        val queued = io.submit {
            flushQueued.set(false)
            flushPlayers()
            flushChatLog()
        }
        if (!queued) flushQueued.set(false)
    }

    private fun flushPlayers() {
        val current = storage ?: return
        for (uuid in dirty.keys) {
            val data = dirty.remove(uuid) ?: continue
            runCatching { current.savePlayer(data) }.onFailure(::warn)
        }
    }

    private fun flushChatLog() {
        val current = storage ?: return
        while (true) {
            val batch = ArrayList<ChatLogEntry>(chatLogBatch)
            while (batch.size < chatLogBatch) {
                batch += chatLog.poll() ?: break
            }
            if (batch.isEmpty()) return
            chatLogSize.addAndGet(-batch.size)
            runCatching { current.logChat(batch) }.onFailure { failure ->
                warn(failure)
                // ponytail: the batch goes back for the next flush, so a hiccup costs order, not rows
                if (chatLogSize.get() + batch.size <= queueCapacity) {
                    chatLog += batch
                    chatLogSize.addAndGet(batch.size)
                } else {
                    io.noteDrop()
                }
                return
            }
        }
    }

    private fun load(uuid: UUID): PlayerData =
        runCatching { storage?.loadPlayer(uuid) }.onFailure(::warn).getOrNull() ?: PlayerData.fresh(uuid)

    private fun warn(throwable: Throwable) {
        plugin.logger.warning("Player storage operation failed: ${throwable.message}")
    }
}
