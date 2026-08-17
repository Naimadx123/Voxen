package zone.vao.voxen.network

data class BrokerMessage(
    val id: String?,
    val server: String?,
    val channel: String?,
    val sender: String?,
    val component: String?,
    val content: String? = null,
    val mm: String? = null,
    val type: String? = null,
    val target: String? = null,
    val senderUuid: String? = null,
    val targetUuid: String? = null,
    val status: String? = null,
    val replyTo: String? = null,
    val flags: String? = null,
    val expiresAt: Long? = null,
    val createdAt: Long? = null,
)
