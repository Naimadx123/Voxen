package zone.vao.voxen.config

data class PrivateMessagesConfig(
    val enabled: Boolean,
    val senderFormat: String,
    val receiverFormat: String,
    val spyFormat: String,
    val notifyMonitored: Boolean,
    val sound: SoundConfig,
)
