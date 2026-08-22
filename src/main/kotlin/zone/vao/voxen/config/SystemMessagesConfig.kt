package zone.vao.voxen.config

data class SystemMessagesConfig(
    val enabled: Boolean,
    val respectVanish: Boolean,
    val events: Map<Kind, Event>,
) {

    fun event(kind: Kind): Event? = events[kind]?.takeIf { enabled && it.enabled && it.format.isNotEmpty() }

    fun handles(kind: Kind): Boolean = enabled && events[kind]?.enabled == true

    enum class Kind(val id: String) {
        JOIN("join"),
        FIRST_JOIN("first-join"),
        QUIT("quit"),
        DEATH("death"),
        ADVANCEMENT("advancement"),
        SERVER_SWITCH("server-switch");

        companion object {
            fun from(value: String?): Kind? = entries.firstOrNull { it.id == value?.trim()?.lowercase() }
        }
    }

    data class Event(
        val enabled: Boolean,
        val format: String,
        val channel: String,
        val crossServer: Boolean,
        val discord: Boolean,
        val delayMillis: Long,
    )
}
