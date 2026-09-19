package zone.vao.voxen

import java.util.UUID

/**
 * Immutable snapshot of one piece of mail. Values reflect the moment the
 * snapshot was taken; fetch a fresh mailbox from [VoxenApi.mailbox] when
 * needed.
 *
 * [readAt] is null while the recipient has not opened their mail yet.
 */
data class MailInfo(
    val id: UUID,
    val recipient: UUID,
    val sender: UUID,
    val senderName: String,
    val content: String,
    val server: String,
    val createdAt: Long,
    val readAt: Long?,
)
