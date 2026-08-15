package zone.vao.voxen.config

data class EmotesConfig(
    val enabled: Boolean,
    val requirePermission: Boolean,
    val emotes: Map<String, String>,
) {
    fun apply(content: String, hasPermission: (String) -> Boolean): String {
        if (!enabled || emotes.isEmpty() || !content.contains(':')) return content
        val allowsAll = !requirePermission || hasPermission(PERMISSION)
        return PATTERN.replace(content) { match ->
            val name = match.groupValues[1].lowercase()
            val emote = emotes[name]
            when {
                emote == null -> match.value
                allowsAll || hasPermission("$PERMISSION.$name") -> emote
                else -> match.value
            }
        }
    }

    companion object {
        const val PERMISSION = "voxen.chat.emote"
        private val PATTERN = Regex(":([a-z0-9_+-]{1,32}):", RegexOption.IGNORE_CASE)
    }
}
