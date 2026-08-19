package zone.vao.voxen.config

data class AiModerationConfig(
    val enabled: Boolean,
    val endpoint: String,
    val headers: Map<String, String>,
    val model: String,
    val label: String,
    val requestBody: String,
    val scorePath: String,
    val timeoutMillis: Long,
    val queueSize: Int,
    val minLength: Int,
    val rules: List<Rule>,
) {

    fun ruleFor(score: Double): Rule? = rules.firstOrNull { score >= it.score }

    data class Rule(
        val score: Double,
        val actions: Set<Action>,
        val commands: List<String>,
    )

    enum class Action {
        DELETE,
        KICK,
        WARN,
        REPORT;

        companion object {
            fun from(value: String): Action? =
                entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
        }
    }
}
