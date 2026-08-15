package zone.vao.voxen.party

import java.util.UUID

data class PartyRecord(
    val id: UUID,
    val name: String,
    val leader: UUID,
    val members: Set<UUID>,
)
