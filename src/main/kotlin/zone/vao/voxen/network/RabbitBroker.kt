package zone.vao.voxen.network

import com.rabbitmq.client.CancelCallback
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.DeliverCallback
import zone.vao.voxen.config.NetworkConfig
import java.util.logging.Logger

class RabbitBroker(
    private val config: NetworkConfig.Rabbit,
    private val serverId: String,
    private val reconnectSeconds: Long,
    private val timeoutMillis: Long,
    private val logger: Logger,
) : MessageBroker {

    private val broadcast = Addresses.broadcast(config.exchange)
    private val own = Addresses.server(config.exchange, serverId)

    @Volatile
    private var running = true

    @Volatile
    private var connection: Connection? = null

    @Volatile
    private var channel: Channel? = null

    private var thread: Thread? = null

    override fun start(onMessage: (String) -> Unit) {
        thread = Thread({
            var warned = false
            while (running && connection == null) {
                try {
                    val factory = ConnectionFactory().apply {
                        host = config.host
                        port = config.port
                        username = config.username
                        password = config.password
                        virtualHost = config.virtualHost
                        connectionTimeout = timeoutMillis.toInt()
                        isAutomaticRecoveryEnabled = true
                        networkRecoveryInterval = reconnectSeconds * 1000L
                    }
                    val conn = factory.newConnection("voxen")
                    val ch = conn.createChannel()
                    ch.exchangeDeclare(config.exchange, "topic", false)
                    val queue = ch.queueDeclare("", false, true, true, emptyMap()).queue
                    ch.queueBind(queue, config.exchange, broadcast)
                    ch.queueBind(queue, config.exchange, own)
                    ch.basicConsume(
                        queue,
                        true,
                        DeliverCallback { _, delivery ->
                            runCatching { onMessage(String(delivery.body, Charsets.UTF_8)) }
                        },
                        CancelCallback { },
                    )
                    connection = conn
                    channel = ch
                    logger.info("Connected to RabbitMQ; bound to '$broadcast' and '$own' on exchange '${config.exchange}'.")
                } catch (ex: Exception) {
                    if (running && !warned) {
                        logger.warning("RabbitMQ connection failed (${ex.javaClass.simpleName}); retrying every ${reconnectSeconds}s.")
                        warned = true
                    }
                    runCatching { Thread.sleep(reconnectSeconds * 1000L) }
                }
            }
        }, "voxen-rabbitmq").apply {
            isDaemon = true
            start()
        }
    }

    override fun publish(payload: String, route: String?) {
        val key = if (route == null) broadcast else Addresses.server(config.exchange, route)
        channel?.basicPublish(config.exchange, key, null, payload.toByteArray(Charsets.UTF_8))
    }

    override fun close() {
        running = false
        runCatching { thread?.interrupt() }
        runCatching { channel?.close() }
        runCatching { connection?.close() }
        channel = null
        connection = null
    }
}
