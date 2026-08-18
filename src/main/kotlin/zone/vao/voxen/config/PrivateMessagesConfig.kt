package zone.vao.voxen.config

data class PrivateMessagesConfig(
    val enabled: Boolean,
    val senderFormat: String,
    val receiverFormat: String,
    val spyFormat: String,
    val notifyMonitored: Boolean,
    val respectMutes: Boolean,
    val sound: SoundConfig,
    val group: GroupConfig,
)

data class GroupConfig(
    val enabled: Boolean,
    val maxMembers: Int,
    val idleMillis: Long,
    val format: String,
)
