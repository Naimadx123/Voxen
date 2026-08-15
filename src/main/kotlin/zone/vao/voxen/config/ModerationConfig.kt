package zone.vao.voxen.config

import java.util.regex.Pattern

data class ModerationConfig(
    val cooldownMillis: Long,
    val repeatEnabled: Boolean,
    val repeatWindowMillis: Long,
    val filterEnabled: Boolean,
    val filterMode: FilterMode,
    val censorReplacement: Char,
    val blockedWords: List<String>,
    val blockedPatterns: List<Pattern>,
    val chatClearLines: Int,
) {
    enum class FilterMode {
        BLOCK,
        CENSOR;

        companion object {
            fun from(value: String?): FilterMode =
                entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: BLOCK
        }
    }
}
