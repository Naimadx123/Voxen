package zone.vao.voxen.network

import com.google.gson.Gson
import org.bukkit.plugin.java.JavaPlugin
import zone.vao.voxen.config.NetworkConfig
import java.util.concurrent.ConcurrentHashMap
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

    @Volatile
    var onModerationMessage: ((BrokerMessage) -> Unit)? = null

    private val gson = Gson()
    private val warned = ConcurrentHashMap.newKeySet<Envelope.Result.Rejected>()
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
        warned.clear()
        val config = network()
        if (config.transport != NetworkConfig.Transport.NONE && config.secret.isEmpty()) {
            plugin.logger.warning(
                "network.secret in integrations.yml is empty, so incoming cross-server messages are not " +
                    "authenticated. Anyone who can reach the broker can fake chat, private messages and mutes.",
            )
        }
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
            runCatching { current.publish(Envelope.wrap(gson.toJson(message), network().secret)) }
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

    private fun handleIncoming(raw: String) {
        val payload = verify(raw) ?: return
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
        if (message.type == TYPE_MUTE || message.type == TYPE_UNMUTE) {
            onModerationMessage?.invoke(message)
            return
        }
        if (message.channel.isNullOrEmpty() || message.component.isNullOrEmpty()) return
        onChatMessage?.invoke(message)
    }

    private fun markSeen(id: String) {
        synchronized(seen) { seen[id] = true }
    }

    private fun verify(raw: String): String? {
        val config = network()
        val result = Envelope.unwrap(raw, config.secret, config.maxAgeSeconds * 1000L)
        if (result is Envelope.Result.Ok) return result.payload
        val reason = result as Envelope.Result.Rejected
        if (warned.add(reason)) plugin.logger.warning("${explain(reason)} Later drops for the same reason are not logged.")
        return null
    }

    private fun explain(reason: Envelope.Result.Rejected): String = when (reason) {
        Envelope.Result.Rejected.TOO_BIG ->
            "Dropped a cross-server message larger than ${Envelope.MAX_BYTES / 1024} KiB."
        Envelope.Result.Rejected.MALFORMED ->
            "Dropped a cross-server message that is not a Voxen envelope. Something else is publishing on the same channel."
        Envelope.Result.Rejected.VERSION ->
            "Dropped a cross-server message from another Voxen protocol version. Run the same Voxen build everywhere."
        Envelope.Result.Rejected.SIGNATURE ->
            "Dropped a cross-server message with a bad or missing signature. Every server must share the same network.secret in integrations.yml."
        Envelope.Result.Rejected.EXPIRED ->
            "Dropped a cross-server message that is too old or dated in the future. Check that the server clocks agree, or raise network.max-age-seconds."
    }

    companion object {
        const val TYPE_PM = "pm"
        const val TYPE_PM_ACK = "pm_ack"
        const val TYPE_PM_SPY = "pm_spy"
        const val TYPE_MUTE = "mute"
        const val TYPE_UNMUTE = "unmute"
    }
}
