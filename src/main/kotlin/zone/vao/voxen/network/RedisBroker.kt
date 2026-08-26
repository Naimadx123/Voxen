package zone.vao.voxen.network

import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPubSub
import zone.vao.voxen.config.NetworkConfig
import java.util.logging.Logger

class RedisBroker(
    private val config: NetworkConfig.Redis,
    private val serverId: String,
    private val reconnectSeconds: Long,
    private val timeoutMillis: Long,
    private val logger: Logger,
) : MessageBroker {

    private val broadcast = Addresses.broadcast(config.channel)
    private val own = Addresses.server(config.channel, serverId)

    @Volatile
    private var running = true

    @Volatile
    private var subscriber: Jedis? = null

    @Volatile
    private var pubSub: JedisPubSub? = null

    private var pool: JedisPool? = null
    private var thread: Thread? = null

    private fun clientConfig(): DefaultJedisClientConfig {
        val builder = DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(timeoutMillis.toInt())
            .socketTimeoutMillis(0)
            .ssl(config.ssl)
        if (config.username.isNotEmpty()) builder.user(config.username)
        if (config.password.isNotEmpty()) builder.password(config.password)
        return builder.build()
    }

    override fun start(onMessage: (String) -> Unit) {
        val address = HostAndPort(config.host, config.port)
        pool = JedisPool(
            address,
            DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(timeoutMillis.toInt())
                .socketTimeoutMillis(timeoutMillis.toInt())
                .ssl(config.ssl)
                .apply {
                    if (config.username.isNotEmpty()) user(config.username)
                    if (config.password.isNotEmpty()) password(config.password)
                }
                .build(),
        )
        thread = Thread({
            var warned = false
            while (running) {
                try {
                    val jedis = Jedis(address, clientConfig())
                    subscriber = jedis
                    val listener = object : JedisPubSub() {
                        override fun onMessage(channel: String, message: String) {
                            runCatching { onMessage(message) }
                        }
                    }
                    pubSub = listener
                    warned = false
                    logger.info("Connected to Redis; subscribed to '$broadcast' and '$own'.")
                    jedis.subscribe(listener, broadcast, own)
                } catch (ex: Exception) {
                    if (running && !warned) {
                        logger.warning("Redis connection lost (${ex.javaClass.simpleName}); retrying every ${reconnectSeconds}s.")
                        warned = true
                    }
                }
                if (!running) break
                runCatching { Thread.sleep(reconnectSeconds * 1000L) }
            }
        }, "voxen-redis").apply {
            isDaemon = true
            start()
        }
    }

    override fun connected(): Boolean = subscriber?.isConnected == true

    override fun publish(payload: String, route: String?): Boolean {
        val current = pool ?: return false
        current.resource.use {
            it.publish(if (route == null) broadcast else Addresses.server(config.channel, route), payload)
        }
        return true
    }

    override fun close() {
        running = false
        runCatching { pubSub?.unsubscribe() }
        runCatching { subscriber?.close() }
        runCatching { thread?.interrupt() }
        runCatching { pool?.close() }
    }
}
