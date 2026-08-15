package zone.vao.voxen

import java.util.UUID

/**
 * Immutable snapshot of a party. Values reflect the moment the snapshot was
 * taken; fetch a fresh one from [VoxenApi.party] when needed.
 *
 * [members] includes the [leader] and may contain offline players.
 */
data class PartyInfo(
    val id: UUID,
    val name: String,
    val leader: UUID,
    val members: Set<UUID>,
)
