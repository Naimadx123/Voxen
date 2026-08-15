package zone.vao.voxen

/**
 * Immutable snapshot of a chat channel. Values reflect the moment the
 * snapshot was taken and do not update after a reload; fetch a fresh one
 * from [VoxenApi.channel] when needed.
 *
 * [type] is one of: global, local, world, staff, party, server, custom.
 * [radius] only matters for local channels (0 means unlimited).
 * An empty [worlds] set means the channel works in every world.
 */
data class ChannelInfo(
    val id: String,
    val displayName: String,
    val type: String,
    val enabled: Boolean,
    val readOnly: Boolean,
    val crossServer: Boolean,
    val radius: Int,
    val worlds: Set<String>,
)
