package zone.vao.voxen.config

data class MailConfig(
    val enabled: Boolean,
    val maxPerPlayer: Int,
    val expireDays: Int,
    val notifyOnJoin: Boolean,
    val allowWhenOnline: Boolean,
    val cooldownMillis: Long,
)
