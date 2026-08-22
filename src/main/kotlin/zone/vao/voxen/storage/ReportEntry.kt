package zone.vao.voxen.storage

import java.util.UUID

data class ReportEntry(
    val id: UUID,
    val target: UUID,
    val targetName: String,
    val reporter: UUID,
    val reporterName: String,
    val reason: String,
    val server: String,
    val channel: String?,
    val messageId: UUID?,
    val messageContent: String?,
    val messageAt: Long?,
    val status: Status,
    val handler: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {

    enum class Status {
        OPEN,
        CLAIMED,
        RESOLVED,
        DISMISSED;

        val id: String
            get() = name.lowercase()

        val pending: Boolean
            get() = this == OPEN || this == CLAIMED

        companion object {
            val PENDING = listOf(OPEN, CLAIMED)

            fun from(value: String?): Status? = entries.firstOrNull { it.id == value?.trim()?.lowercase() }
        }
    }
}
