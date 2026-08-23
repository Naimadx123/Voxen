package zone.vao.voxen

import java.util.UUID

/**
 * Everything worth knowing about one report: the report itself, the chat
 * around the message it points at, and what moderators have done to it.
 *
 * [context] is empty when the report was filed without a message, and is
 * ordered oldest first. The reported line is the one whose
 * [ChatLine.id] equals [ReportInfo.messageId].
 */
data class ReportCase(
    val report: ReportInfo,
    val context: List<ChatLine>,
    val history: List<AuditEntry>,
) {

    /** One line of the audit trail, oldest first. */
    data class AuditEntry(
        val id: UUID,
        val actor: String,
        val action: String,
        val detail: String?,
        val createdAt: Long,
    )
}
