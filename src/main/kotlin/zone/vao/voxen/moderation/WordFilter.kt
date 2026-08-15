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
        return apply(content, ranges, config.filterMode, config.censorReplacement)
    }

    fun checkLinks(content: String): Result {
        val config = moderation()
        if (!config.linksEnabled || !content.contains('.')) return Result.Clean

        val ranges = mutableListOf<IntRange>()
        for (match in URL.findAll(content)) {
            if (!isWhitelisted(match.value, config.linkWhitelist)) ranges += match.range
        }
        if (config.linkIps) {
            for (match in IP.findAll(content)) ranges += match.range
        }
        return apply(content, ranges, config.linkMode, config.censorReplacement)
    }

    private fun apply(
        content: String,
        ranges: List<IntRange>,
        mode: ModerationConfig.FilterMode,
        replacement: Char,
    ): Result {
        if (ranges.isEmpty()) return Result.Clean
        if (mode == ModerationConfig.FilterMode.BLOCK) return Result.Blocked

        val chars = content.toCharArray()
        for (range in ranges) {
            for (i in range) {
                if (i in chars.indices && !chars[i].isWhitespace()) chars[i] = replacement
            }
        }
        return Result.Censored(String(chars))
    }

    private fun isWhitelisted(match: String, whitelist: Set<String>): Boolean {
        if (whitelist.isEmpty()) return false
        val host = match.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return whitelist.any { host == it || host.endsWith(".$it") }
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
        val URL = Regex("""(?:[a-z][a-z0-9+.-]*://)?(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}(?::\d{1,5})?(?:/\S*)?""", RegexOption.IGNORE_CASE)
        val IP = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?\b""")
    }
}
