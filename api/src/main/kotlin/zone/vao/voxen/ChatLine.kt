package zone.vao.voxen

import java.util.UUID

/**
 * One line from the chat log, as it was stored.
 *
 * [id] matches the message id a report points at, so the reported line can be
 * picked out of a stretch of context.
 */
data class ChatLine(
    val id: UUID?,
    val player: UUID,
    val playerName: String,
    val channelId: String,
    val content: String,
    val server: String,
    val createdAt: Long,
)
