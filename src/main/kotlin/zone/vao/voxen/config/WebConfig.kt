package zone.vao.voxen.config

data class WebConfig(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val title: String,
    val realm: String,
    val threads: Int,
    val maxLoginAttempts: Int,
    val lockoutMillis: Long,
    val users: List<User>,
    val labels: Map<String, String>,
) {

    fun user(name: String): User? = users.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun label(key: String, fallback: String): String = labels[key]?.ifEmpty { null } ?: fallback

    data class User(
        val name: String,
        val password: String,
        val permissions: Set<String>,
    ) {

        fun allows(permission: String): Boolean = permissions.any { granted ->
            granted == "*" ||
                granted.equals(permission, ignoreCase = true) ||
                (granted.endsWith(".*") && permission.startsWith(granted.dropLast(1), ignoreCase = true))
        }
    }
}
