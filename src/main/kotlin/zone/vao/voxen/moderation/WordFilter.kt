package zone.vao.voxen.moderation

import zone.vao.voxen.config.ModerationConfig

class WordFilter(
    private val moderation: () -> ModerationConfig,
) {

    sealed interface Result {
        data object Clean : Result
        data object Blocked : Result
        data class Censored(val content: String) : Result
    }

    fun check(content: String): Result {
        val config = moderation()
        if (!config.filterEnabled) return Result.Clean

        val ranges = mutableListOf<IntRange>()
        val lower = content.lowercase()
        val index = config.wordIndex
        if (index.isNotEmpty()) {
            for (i in lower.indices) {
                val candidates = index[lower[i]] ?: continue
                for (word in candidates) {
                    if (lower.startsWith(word, i)) ranges += i until i + word.length
                }
            }
        }
        for (pattern in config.blockedPatterns) {
            val matcher = pattern.matcher(content)
            while (matcher.find()) {
                if (matcher.end() > matcher.start()) ranges += matcher.start() until matcher.end()
            }
        }
        if (ranges.isEmpty()) return Result.Clean
        if (config.filterMode == ModerationConfig.FilterMode.BLOCK) return Result.Blocked

        val chars = content.toCharArray()
        for (range in ranges) {
            for (i in range) {
                if (i in chars.indices && !chars[i].isWhitespace()) chars[i] = config.censorReplacement
            }
        }
        return Result.Censored(String(chars))
    }
}
