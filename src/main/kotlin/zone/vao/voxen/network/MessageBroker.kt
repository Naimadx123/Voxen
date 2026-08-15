package zone.vao.voxen.network

interface MessageBroker : AutoCloseable {
    fun start(onMessage: (String) -> Unit)
    fun publish(payload: String)
    override fun close()
}
