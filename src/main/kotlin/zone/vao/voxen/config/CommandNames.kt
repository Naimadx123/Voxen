package zone.vao.voxen.config

data class CommandNames(
    val message: List<String>,
    val reply: List<String>,
    val channel: List<String>,
    val ignore: List<String>,
    val ignoreList: List<String>,
    val party: List<String>,
    val chatToggle: List<String>,
    val language: List<String>,
    val filter: List<String>,
    val nickname: List<String>,
    val realName: List<String>,
) {
    fun primary(names: List<String>, fallback: String): String =
        names.firstOrNull()?.lowercase() ?: fallback
}
