package zone.vao.voxen.config

import java.util.regex.Pattern

data class TagsConfig(
    val mode: UnauthorizedMode,
    val legacyEnabled: Boolean,
    val rules: Map<String, TagRule>,
    val custom: Map<String, TagRule> = emptyMap(),
) {
    enum class UnauthorizedMode {
        STRIP,
        ESCAPE;

        companion object {
            fun from(value: String?): UnauthorizedMode =
                entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: ESCAPE
        }
    }

    data class TagRule(
        val name: String,
        val enabled: Boolean,
        val permission: String,
        val aliases: List<String>,
        val blockedParams: List<Pattern>,
        val actionPermissions: Map<String, String>,
    ) {
        fun names(): List<String> = listOf(name) + aliases
    }
}
