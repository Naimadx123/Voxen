package zone.vao.voxen.config

data class MentionsConfig(
    val enabled: Boolean,
    val highlight: String,
    val cooldownMillis: Long,
    val sound: SoundConfig,
)
