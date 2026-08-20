package zone.vao.voxen.storage

import java.util.UUID

data class MailEntry(
    val id: UUID,
    val recipient: UUID,
    val senderUuid: UUID,
    val senderName: String,
    val content: String,
    val server: String,
    val createdAt: Long,
    val readAt: Long? = null,
)
