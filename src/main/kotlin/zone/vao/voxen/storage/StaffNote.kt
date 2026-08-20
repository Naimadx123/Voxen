package zone.vao.voxen.storage

import java.util.UUID

data class StaffNote(
    val id: UUID,
    val target: UUID,
    val targetName: String,
    val author: String,
    val content: String,
    val kind: Kind,
    val createdAt: Long,
) {

    enum class Kind {
        WARN,
        NOTE;

        val id: String
            get() = name.lowercase()

        companion object {
            fun from(value: String?): Kind? = entries.firstOrNull { it.id == value?.trim()?.lowercase() }
        }
    }
}
