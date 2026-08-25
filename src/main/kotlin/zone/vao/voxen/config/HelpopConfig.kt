package zone.vao.voxen.config

data class HelpopConfig(
    val enabled: Boolean,
    val mode: Mode,
    val cooldownMillis: Long,
    val maxLength: Int,
    val maxOpen: Int,
    val queueLimit: Int,
    val historyLimit: Int,
    val keepDays: Int,
    val dialogs: Boolean,
    val web: Boolean,
    val notifyStaff: Boolean,
) {

    val tickets: Boolean
        get() = enabled && mode == Mode.TICKETS

    enum class Mode {
        BROADCAST,
        TICKETS;

        val id: String
            get() = name.lowercase()

        companion object {
            fun from(value: String?): Mode =
                entries.firstOrNull { it.id == value?.trim()?.lowercase() } ?: BROADCAST
        }
    }
}
