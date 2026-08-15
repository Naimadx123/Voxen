package zone.vao.voxen.moderation

import zone.vao.voxen.config.ModerationConfig
import java.text.Normalizer

class WordFilter(
    private val moderation: () -> ModerationConfig,
) {

    sealed interface Result {
        data object Clean : Result
        data object Blocked : Result
        data class Censored(val content: String) : Result
    }

    private class Normalized(val text: String, val map: IntArray)

    fun check(content: String): Result {
        val config = moderation()
        if (!config.filterEnabled) return Result.Clean

        val ranges = mutableListOf<IntRange>()
        val lower = content.lowercase()
        val index = config.wordIndex
        if (index.isNotEmpty()) {
            val normalized = normalize(lower, config)
            val text = normalized?.text ?: lower
            for (i in text.indices) {
                val candidates = index[text[i]] ?: continue
                for (word in candidates) {
                    if (text.startsWith(word, i)) {
                        val end = i + word.length - 1
                        ranges += if (normalized != null) normalized.map[i]..normalized.map[end] else i..end
                    }
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

    private fun normalize(lower: String, config: ModerationConfig): Normalized? {
        if (!config.normalizeLeet && !config.normalizeDiacritics && !config.normalizeSeparators) return null
        val builder = StringBuilder(lower.length)
        val map = IntArray(lower.length)
        var length = 0
        for (i in lower.indices) {
            var ch = lower[i]
            if (config.normalizeLeet) LEET[ch]?.let { ch = it }
            if (config.normalizeDiacritics && ch.code > 127) ch = stripDiacritic(ch)
            if (config.normalizeSeparators && !ch.isLetterOrDigit()) continue
            builder.append(ch)
            map[length++] = i
        }
        return Normalized(builder.toString(), map.copyOf(length))
    }

    private fun stripDiacritic(ch: Char): Char {
        SPECIAL[ch]?.let { return it }
        val decomposed = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
        return decomposed.firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() } ?: ch
    }

    private companion object {
        val LEET = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's')
        val SPECIAL = mapOf('ł' to 'l', 'ø' to 'o', 'đ' to 'd', 'æ' to 'a', 'ß' to 's')
    }
}
