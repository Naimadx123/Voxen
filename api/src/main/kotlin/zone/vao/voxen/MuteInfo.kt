package zone.vao.voxen

import java.util.UUID

/**
 * A mute a player currently has.
 *
 * [channelId] is null for a mute covering every channel, and [expiresAt] is
 * null when it never runs out.
 */
data class MuteInfo(
    val target: UUID,
    val targetName: String,
    val channelId: String?,
    val reason: String?,
    val moderator: String,
    val expiresAt: Long?,
    val createdAt: Long,
) {

    /** True while the mute has no end. */
    fun isPermanent(): Boolean = expiresAt == null
}
