package zone.vao.voxen.storage

import java.util.UUID

data class ReportAction(
    val id: UUID,
    val report: UUID,
    val actor: String,
    val action: String,
    val detail: String?,
    val createdAt: Long,
)
