package zone.vao.voxen.network

import com.google.gson.Gson
import zone.vao.voxen.config.NetworkConfig
import zone.vao.voxen.util.WorkQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class BrokerService(
    private val logger: Logger,
    private val network: () -> NetworkConfig,
    queueCapacity: Int = 1000,
) {

    @Volatile
    private var broker: MessageBroker? = null

    @Volatile
    var onChatMessage: ((BrokerMessage) -> Unit)? = null

    @Volatile
    var onPmMessage: ((BrokerMessage) -> Unit)? = null

    @Volatile
    var onModerationMessage: ((BrokerMessage) -> Unit)? = null

    @Volatile
    var onPresenceMessage: ((BrokerMessage) -> Unit)? = null

    @Volatile
    var onSystemMessage: ((BrokerMessage) -> Unit)? = null

    @Volatile
    private var lastMessageAt = 0L

    private val gson = Gson()
    private val warned = ConcurrentHashMap.newKeySet<Envelope.Result.Rejected>()
    private val io = WorkQueue(
        "voxen-network",
        queueCapacity,
        logger,
        "The broker is unreachable or too slow, so cross-server messages are being dropped.",
    )
    private val seen = LinkedHashMap<String, Long>(256)

    fun active(): Boolean = broker?.connected() == true

    fun transportName(): String = network().transport.name.lowercase()

    fun stats(): Triple<Int, Long, Long> = Triple(io.pending(), io.dropped(), lastMessageAt)

    fun start() {
        stop()
        warned.clear()
        val config = network()
        if (config.transport != NetworkConfig.Transport.NONE && config.secret.isEmpty()) {
            if (!config.allowUnsigned) {
                logger.severe(
                    "network.secret in integrations.yml is empty, so cross-server messages could not be " +
                        "authenticated and anyone who can reach the broker could fake chat, private messages and " +
                        "mutes. The network stays off. Set network.secret on every server, or accept the risk with " +
                        "network.allow-unsigned: true.",
                )
                return
            }
            logger.warning(
                "network.allow-unsigned is true and network.secret is empty, so incoming cross-server messages " +
                    "are not authenticated. Anyone who can reach the broker can fake chat, private messages and mutes.",
            )
        }
        val created = when (config.transport) {
            NetworkConfig.Transport.NONE -> return
            NetworkConfig.Transport.REDIS ->
                RedisBroker(config.redis, config.serverId, config.reconnectSeconds, config.timeoutMillis, logger)
            NetworkConfig.Transport.NATS ->
                NatsBroker(config.nats, config.serverId, config.reconnectSeconds, config.timeoutMillis, logger)
            NetworkConfig.Transport.RABBITMQ ->
                RabbitBroker(config.rabbit, config.serverId, config.reconnectSeconds, config.timeoutMillis, logger)
        }
        connect(created, config.transport.name.lowercase())
    }

    internal fun connect(candidate: MessageBroker, transportName: String) {
        val started = runCatching { candidate.start(::handleIncoming) }
            .onFailure {
                logger.warning("Failed to start the $transportName transport: ${it.message}. Cross-server chat is disabled.")
                runCatching { candidate.close() }
            }
            .isSuccess
        if (started) broker = candidate
    }

    fun publish(message: BrokerMessage) {
        val current = broker ?: return
        message.id?.let(::markSeen)
        io.submit {
            val sent = runCatching { current.publish(Envelope.wrap(gson.toJson(message), network().secret), message.route) }
                .onFailure { logger.warning("Failed to publish a cross-server message: ${it.message}") }
                .getOrDefault(false)
            if (!sent) io.noteDrop()
        }
    }

    fun stop() {
        runCatching { broker?.close() }
        broker = null
    }

    fun shutdown() {
        stop()
        io.shutdown(3)
    }

    private fun handleIncoming(raw: String) {
        val payload = verify(raw) ?: return
        val message = runCatching { gson.fromJson(payload, BrokerMessage::class.java) }.getOrNull() ?: return
        lastMessageAt = System.currentTimeMillis()
        val id = message.id
        if (id.isNullOrEmpty() || message.server == network().serverId) return
        if (!markSeen(id)) return
        if (message.type == TYPE_PM || message.type == TYPE_PM_ACK || message.type == TYPE_PM_SPY) {
            onPmMessage?.invoke(message)
            return
        }
        if (message.type in PRESENCE_TYPES) {
            onPresenceMessage?.invoke(message)
            return
        }
        if (message.type == TYPE_SYSTEM) {
            onSystemMessage?.invoke(message)
            return
        }
        if (message.type == TYPE_MUTE || message.type == TYPE_UNMUTE) {
            onModerationMessage?.invoke(message)
            return
        }
        if (message.channel.isNullOrEmpty() || message.component.isNullOrEmpty()) return
        onChatMessage?.invoke(message)
    }

    private fun markSeen(id: String): Boolean {
        val now = System.currentTimeMillis()
        val expiresAt = now + ((network().maxAgeSeconds * 1000L).takeIf { it > 0 } ?: DEFAULT_REPLAY_MILLIS)
        synchronized(seen) {
            val entries = seen.entries.iterator()
            while (entries.hasNext()) {
                if (entries.next().value > now) break
                entries.remove()
            }
            while (seen.size >= MAX_SEEN) {
                val oldest = seen.entries.iterator()
                oldest.next()
                oldest.remove()
            }
            return seen.put(id, expiresAt) == null
        }
    }

    private fun verify(raw: String): String? {
        val config = network()
        val result = Envelope.unwrap(raw, config.secret, config.maxAgeSeconds * 1000L)
        if (result is Envelope.Result.Ok) return result.payload
        val reason = result as Envelope.Result.Rejected
        if (warned.add(reason)) logger.warning("${explain(reason)} Later drops for the same reason are not logged.")
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
        private const val DEFAULT_REPLAY_MILLIS = 60_000L
        private const val MAX_SEEN = 100_000

        const val TYPE_PM = "pm"
        const val TYPE_PM_ACK = "pm_ack"
        const val TYPE_PM_SPY = "pm_spy"
        const val TYPE_MUTE = "mute"
        const val TYPE_UNMUTE = "unmute"
        const val TYPE_PRESENCE_JOIN = "presence_join"
        const val TYPE_PRESENCE_QUIT = "presence_quit"
        const val TYPE_PRESENCE_SYNC = "presence_sync"
        const val TYPE_SYSTEM = "system"
        private val PRESENCE_TYPES = setOf(TYPE_PRESENCE_JOIN, TYPE_PRESENCE_QUIT, TYPE_PRESENCE_SYNC)
    }
}
