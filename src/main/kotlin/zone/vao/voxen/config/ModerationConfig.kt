package zone.vao.voxen.config

import java.util.regex.Pattern

data class ModerationConfig(
    val cooldownMillis: Long,
    val repeatEnabled: Boolean,
    val repeatWindowMillis: Long,
    val similarityEnabled: Boolean = false,
    val similarityThreshold: Double = 0.85,
    val similarityHistory: Int = 3,
    val floodEnabled: Boolean = true,
    val floodMaxRun: Int = 8,
    val floodMaxWordLength: Int = 30,
    val filterEnabled: Boolean,
    val filterMode: FilterMode,
    val censorReplacement: Char,
    val blockedWords: List<String>,
    val blockedPatterns: List<Pattern>,
    val chatClearLines: Int,
    val normalizeLeet: Boolean = true,
    val normalizeDiacritics: Boolean = true,
    val normalizeSeparators: Boolean = true,
    val normalizeRepeated: Boolean = true,
    val linksEnabled: Boolean = false,
    val linkMode: FilterMode = FilterMode.BLOCK,
    val linkIps: Boolean = true,
    val linkObfuscated: Boolean = true,
    val linkWhitelist: Set<String> = emptySet(),
    val slowmodeEnabled: Boolean = true,
    val historyEnabled: Boolean = false,
    val historyKeepDays: Int = 14,
    val historyEntries: Int = 15,
    val maxLength: Int = 0,
    val cooldownAffectsPm: Boolean = false,
    val repeatAffectsPm: Boolean = false,
    val floodAffectsPm: Boolean = true,
    val filterAffectsPm: Boolean = true,
    val linksAffectsPm: Boolean = true,
    val historyAffectsPm: Boolean = false,
    val maxLengthAffectsPm: Boolean = true,
) {

    val spamAffectsPm: Boolean
        get() = cooldownAffectsPm || repeatAffectsPm || floodAffectsPm

    val similarityThresholdPercent: String
        get() = Math.round(similarityThreshold * 100).toString()

    val wordIndex: Map<Char, List<String>> by lazy {
        val words = if (normalizeRepeated) blockedWords.map(::collapseRuns).distinct() else blockedWords
        words.groupBy { it.first() }
    }

    private fun collapseRuns(word: String): String {
        val builder = StringBuilder(word.length)
        for (ch in word) {
            if (builder.isNotEmpty() && builder.last() == ch) continue
            builder.append(ch)
        }
        return builder.toString()
    }

    enum class FilterMode {
        BLOCK,
        CENSOR;

        companion object {
            fun from(value: String?): FilterMode =
                entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: BLOCK
        }
    }
}
