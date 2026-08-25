package zone.vao.voxen.storage

import java.util.UUID

data class TicketMessage(
    val id: UUID,
    val ticket: UUID,
    val author: String,
    val staff: Boolean,
    val content: String,
    val createdAt: Long,
)
