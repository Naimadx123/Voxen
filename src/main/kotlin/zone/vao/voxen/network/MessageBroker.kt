package zone.vao.voxen.network

interface MessageBroker : AutoCloseable {
    fun start(onMessage: (String) -> Unit)

    fun connected(): Boolean

    fun publish(payload: String, route: String?): Boolean

    override fun close()
}

object Addresses {

    private val UNSAFE = Regex("[^a-z0-9_-]")

    fun broadcast(base: String): String = "$base.broadcast"

    fun server(base: String, serverId: String): String = "$base.server.${slug(serverId)}"

    fun slug(serverId: String): String = serverId.trim().lowercase().replace(UNSAFE, "-")
}
