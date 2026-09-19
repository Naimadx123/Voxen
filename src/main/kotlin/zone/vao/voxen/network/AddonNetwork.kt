package zone.vao.voxen.network

import zone.vao.voxen.NetworkListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class AddonNetwork(
    private val broker: BrokerService,
    private val serverId: () -> String,
    private val logger: Logger,
) {

    private val listeners = ConcurrentHashMap<String, NetworkListener>()

    fun send(channel: String, payload: String, server: String?): Boolean {
        val name = channel.lowercase()
        if (!valid(name)) return false
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) return false
        if (!broker.active()) return false
        broker.publish(
            BrokerMessage(
                id = UUID.randomUUID().toString(),
                server = serverId(),
                channel = name,
                sender = null,
                component = null,
                content = payload,
                type = BrokerService.TYPE_ADDON,
                route = server,
            ),
        )
        return true
    }

    fun register(channel: String, listener: NetworkListener): Boolean {
        val name = channel.lowercase()
        if (!valid(name)) return false
        return listeners.putIfAbsent(name, listener) == null
    }

    fun unregister(channel: String) {
        listeners.remove(channel.lowercase())
    }

    fun deliver(message: BrokerMessage) {
        val channel = message.channel ?: return
        val listener = listeners[channel] ?: return
        runCatching { listener.onMessage(channel, message.content.orEmpty(), message.server.orEmpty()) }
            .onFailure { logger.warning("A network listener for '$channel' failed: ${it.message}") }
    }

    private fun valid(channel: String): Boolean =
        channel.length in 1..MAX_CHANNEL_LENGTH && CHANNEL.matches(channel)

    companion object {
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        private const val MAX_CHANNEL_LENGTH = 64
        private val CHANNEL = Regex("[a-z0-9_.-]+")
    }
}
