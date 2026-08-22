package zone.vao.voxen.web

import zone.vao.voxen.config.WebConfig

class WebModule(
    val id: String,
    val title: () -> String,
    val permission: String,
    val enabled: () -> Boolean = { true },
    val render: (WebRequest) -> String,
    val submit: (WebRequest) -> Unit = {},
)

data class WebRequest(
    val module: String,
    val user: WebConfig.User,
    val query: Map<String, String>,
    val form: Map<String, String>,
    val token: String,
) {

    fun param(name: String): String? = (form[name] ?: query[name])?.trim()?.ifEmpty { null }

    fun allows(permission: String): Boolean = user.allows(permission)

    fun link(vararg params: Pair<String, String>): String =
        "/$module" + params.joinToString("&", prefix = "?") { (key, value) -> "$key=${Html.encode(value)}" }
}
