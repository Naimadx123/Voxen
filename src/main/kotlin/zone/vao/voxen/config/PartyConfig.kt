package zone.vao.voxen.config

data class PartyConfig(
    val enabled: Boolean,
    val maxMembers: Int,
    val inviteExpiryMillis: Long,
)
