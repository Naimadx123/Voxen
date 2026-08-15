package zone.vao.voxen.storage

import java.util.UUID

data class ChatLogEntry(
    val uuid: UUID,
    val playerName: String,
    val channel: String,
    val content: String,
    val server: String,
    val createdAt: Long,
)
