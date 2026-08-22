package zone.vao.voxen.storage

import java.util.UUID

data class TicketEntry(
    val id: UUID,
    val player: UUID,
    val playerName: String,
    val subject: String,
    val server: String,
    val status: Status,
    val handler: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {

    enum class Status {
        OPEN,
        ANSWERED,
        CLOSED;

        val id: String
            get() = name.lowercase()

        val active: Boolean
            get() = this != CLOSED

        companion object {
            val ACTIVE = listOf(OPEN, ANSWERED)

            fun from(value: String?): Status? = entries.firstOrNull { it.id == value?.trim()?.lowercase() }
        }
    }
}
