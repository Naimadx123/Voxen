package zone.vao.voxen.network

interface MessageBroker : AutoCloseable {
    fun start(onMessage: (String) -> Unit)

    /** A null route goes to every server, otherwise only to that server id. */
    fun publish(payload: String, route: String?)

    override fun close()
}

/**
 * Every transport uses the same two addresses off one configured base name: one everybody
 * listens on and one per server, so a directed message never reaches the other servers.
 */
object Addresses {

    private val UNSAFE = Regex("[^a-z0-9_-]")

    fun broadcast(base: String): String = "$base.broadcast"

    fun server(base: String, serverId: String): String = "$base.server.${slug(serverId)}"

    fun slug(serverId: String): String = serverId.trim().lowercase().replace(UNSAFE, "-")
}
