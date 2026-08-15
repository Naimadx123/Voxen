package zone.vao.voxen.network

data class BrokerMessage(
    val id: String?,
    val server: String?,
    val channel: String?,
    val sender: String?,
    val component: String?,
    val content: String? = null,
    val mm: String? = null,
)
