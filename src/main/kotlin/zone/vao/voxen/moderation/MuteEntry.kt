package zone.vao.voxen.moderation

import java.util.UUID

data class MuteEntry(
    val uuid: UUID,
    val playerName: String,
    val channel: String?,
    val reason: String?,
    val moderator: String,
    val expiresAt: Long?,
    val createdAt: Long,
) {
    fun expired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt != null && expiresAt <= now

    fun applies(channelId: String?): Boolean =
        channel == null || channel.equals(channelId, ignoreCase = true)
}
