package zone.vao.voxen.network

import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import zone.vao.voxen.config.NetworkConfig
import java.time.Duration
import java.util.logging.Logger

class NatsBroker(
    private val config: NetworkConfig.Nats,
    private val reconnectSeconds: Long,
    private val timeoutMillis: Long,
    private val logger: Logger,
) : MessageBroker {

    @Volatile
    private var running = true

    @Volatile
    private var connection: Connection? = null

    private var thread: Thread? = null

    override fun start(onMessage: (String) -> Unit) {
        thread = Thread({
            var warned = false
            while (running && connection == null) {
                try {
                    val builder = Options.builder()
                        .server(config.url)
                        .connectionTimeout(Duration.ofMillis(timeoutMillis))
                        .maxReconnects(-1)
                        .reconnectWait(Duration.ofSeconds(reconnectSeconds))
                    if (config.username.isNotEmpty()) {
                        builder.userInfo(config.username.toCharArray(), config.password.toCharArray())
                    }
                    val conn = Nats.connect(builder.build())
                    val dispatcher = conn.createDispatcher { message ->
                        runCatching { onMessage(String(message.data, Charsets.UTF_8)) }
                    }
                    dispatcher.subscribe(config.subject)
                    connection = conn
                    logger.info("Connected to NATS; subscribed to '${config.subject}'.")
                } catch (ex: Exception) {
                    if (running && !warned) {
                        logger.warning("NATS connection failed (${ex.javaClass.simpleName}); retrying every ${reconnectSeconds}s.")
                        warned = true
                    }
                    runCatching { Thread.sleep(reconnectSeconds * 1000L) }
                }
            }
        }, "voxen-nats").apply {
            isDaemon = true
            start()
        }
    }

    override fun publish(payload: String) {
        connection?.publish(config.subject, payload.toByteArray(Charsets.UTF_8))
    }

    override fun close() {
        running = false
        runCatching { thread?.interrupt() }
        runCatching { connection?.close() }
        connection = null
    }
}
