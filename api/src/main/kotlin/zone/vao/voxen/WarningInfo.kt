package zone.vao.voxen

import java.util.UUID

/** A warning on a player's record. */
data class WarningInfo(
    val id: UUID,
    val target: UUID,
    val targetName: String,
    val moderator: String,
    val reason: String,
    val createdAt: Long,
)
