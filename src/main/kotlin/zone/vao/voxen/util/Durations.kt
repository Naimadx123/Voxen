package zone.vao.voxen.util

object Durations {

    private val TOKEN = Regex("(\\d+)([smhdw])", RegexOption.IGNORE_CASE)

    fun parseMillis(input: String): Long? {
        val cleaned = input.trim().replace(Regex("\\s"), "")
        if (cleaned.isEmpty()) return null
        cleaned.toLongOrNull()?.let { return if (it >= 0) it * 1000L else null }
        val matches = TOKEN.findAll(cleaned).toList()
        if (matches.isEmpty() || matches.sumOf { it.value.length } != cleaned.length) return null
        return matches.sumOf { it.groupValues[1].toLong() * unitMillis(it.groupValues[2].lowercase()) }
    }

    fun humanize(millis: Long): String {
        if (millis <= 0) return "0s"
        var remaining = millis / 1000
        val weeks = remaining / 604_800; remaining %= 604_800
        val days = remaining / 86_400; remaining %= 86_400
        val hours = remaining / 3_600; remaining %= 3_600
        val minutes = remaining / 60
        val seconds = remaining % 60
        val parts = buildList {
            if (weeks > 0) add("${weeks}w")
            if (days > 0) add("${days}d")
            if (hours > 0) add("${hours}h")
            if (minutes > 0) add("${minutes}m")
            if (seconds > 0) add("${seconds}s")
        }
        return if (parts.isEmpty()) "0s" else parts.joinToString(" ")
    }

    private fun unitMillis(unit: String): Long = when (unit) {
        "s" -> 1_000L
        "m" -> 60_000L
        "h" -> 3_600_000L
        "d" -> 86_400_000L
        "w" -> 604_800_000L
        else -> 0L
    }
}
