package zone.vao.voxen.network

import com.google.gson.Gson
import org.bukkit.plugin.java.JavaPlugin
import zone.vao.voxen.config.NetworkConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BrokerService(
    private val plugin: JavaPlugin,
    private val network: () -> NetworkConfig,
) {

    @Volatile
    private var broker: MessageBroker? = null

    @Volatile
    var onChatMessage: ((BrokerMessage) -> Unit)? = null

    @Volatile
    var onPmMessage: ((BrokerMessage) -> Unit)? = null

    private val gson = Gson()
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "voxen-network").apply { isDaemon = true }
    }
    private val seen = object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 1024
    }

    fun active(): Boolean = broker != null

    fun transportName(): String = network().transport.name.lowercase()

    fun start() {
        stop()
        val config = network()
        val created = when (config.transport) {
            NetworkConfig.Transport.NONE -> return
            NetworkConfig.Transport.REDIS ->
                RedisBroker(config.redis, config.reconnectSeconds, config.timeoutMillis, plugin.logger)
            NetworkConfig.Transport.NATS ->
                NatsBroker(config.nats, config.reconnectSeconds, config.timeoutMillis, plugin.logger)
            NetworkConfig.Transport.RABBITMQ ->
                RabbitBroker(config.rabbit, config.reconnectSeconds, config.timeoutMillis, plugin.logger)
        }
        val started = runCatching { created.start(::handleIncoming) }
            .onFailure {
                plugin.logger.warning("Failed to start the ${config.transport.name.lowercase()} transport: ${it.message}. Cross-server chat is disabled.")
                runCatching { created.close() }
            }
            .isSuccess
        if (started) broker = created
    }

    fun publish(message: BrokerMessage) {
        val current = broker ?: return
        message.id?.let(::markSeen)
        io.execute {
            runCatching { current.publish(gson.toJson(message)) }
                .onFailure { plugin.logger.warning("Failed to publish a cross-server message: ${it.message}") }
        }
    }

    fun stop() {
        runCatching { broker?.close() }
        broker = null
    }

    fun shutdown() {
        stop()
        io.shutdown()
        runCatching { io.awaitTermination(3, TimeUnit.SECONDS) }
    }

    private fun handleIncoming(payload: String) {
        val message = runCatching { gson.fromJson(payload, BrokerMessage::class.java) }.getOrNull() ?: return
        val id = message.id
        if (id.isNullOrEmpty() || message.server == network().serverId) return
        synchronized(seen) {
            if (seen.containsKey(id)) return
            seen[id] = true
        }
        if (message.type == TYPE_PM || message.type == TYPE_PM_ACK || message.type == TYPE_PM_SPY) {
            onPmMessage?.invoke(message)
            return
        }
        if (message.channel.isNullOrEmpty() || message.component.isNullOrEmpty()) return
        onChatMessage?.invoke(message)
    }

    private fun markSeen(id: String) {
        synchronized(seen) { seen[id] = true }
    }

    companion object {
        const val TYPE_PM = "pm"
        const val TYPE_PM_ACK = "pm_ack"
        const val TYPE_PM_SPY = "pm_spy"
    }
}
