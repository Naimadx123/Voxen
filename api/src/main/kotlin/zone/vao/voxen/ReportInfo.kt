package zone.vao.voxen

import java.util.UUID

/**
 * A player report as it is stored. Immutable snapshot, taken when it was read.
 *
 * [channelId], [messageId], [messageContent] and [messageAt] describe the chat
 * message the report points at, and are all null when it was filed without one.
 */
data class ReportInfo(
    val id: UUID,
    val target: UUID,
    val targetName: String,
    val reporter: UUID,
    val reporterName: String,
    val reason: String,
    val server: String,
    val channelId: String?,
    val messageId: UUID?,
    val messageContent: String?,
    val messageAt: Long?,
    val status: Status,
    val moderator: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {

    /** Where a report is in its life: the first two are still in the queue. */
    enum class Status {
        OPEN,
        CLAIMED,
        RESOLVED,
        DISMISSED;

        companion object {
            /** The two statuses that still need a moderator. */
            @JvmField
            val PENDING: List<Status> = listOf(OPEN, CLAIMED)
        }
    }

    /** What a moderator can do to a report. [DELETE] removes it and its audit trail. */
    enum class Action {
        CLAIM,
        RESOLVE,
        DISMISS,
        DELETE,
    }
}
