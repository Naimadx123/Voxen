package zone.vao.voxen

import java.util.UUID

/**
 * A help ticket opened with `/helpop` while the module runs in ticket mode.
 *
 * [moderator] is whoever last answered, and is null while nobody has.
 */
data class TicketInfo(
    val id: UUID,
    val player: UUID,
    val playerName: String,
    val subject: String,
    val server: String,
    val status: Status,
    val moderator: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {

    /** Where a ticket stands. Anything but [CLOSED] still needs someone. */
    enum class Status {
        /** Waiting for the staff. */
        OPEN,

        /** The staff answered and it is the player's turn. */
        ANSWERED,

        /** Settled. The player can read it but not write in it. */
        CLOSED;

        companion object {
            /** The two statuses still in play. */
            @JvmField
            val ACTIVE: List<Status> = listOf(OPEN, ANSWERED)
        }
    }

    /** One message in a ticket, oldest first. [staff] tells the two sides apart. */
    data class Message(
        val id: UUID,
        val author: String,
        val staff: Boolean,
        val content: String,
        val createdAt: Long,
    )
}
