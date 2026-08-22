package zone.vao.voxen

import java.util.UUID

/**
 * A player somewhere on the network. Immutable snapshot.
 *
 * [server] is the network id from `integrations.yml`, and [seenAt] is when
 * that server last said so; local players are always current.
 */
data class NetworkPlayer(
    val uuid: UUID,
    val name: String,
    val server: String,
    val seenAt: Long,
)
