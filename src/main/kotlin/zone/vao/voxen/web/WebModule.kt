package zone.vao.voxen.web

import zone.vao.voxen.PanelRequest
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
    override val page: String,
    val user: WebConfig.User,
    val query: Map<String, String>,
    val form: Map<String, String>,
    override val token: String,
) : PanelRequest {

    override val account: String get() = user.name

    override fun param(name: String): String? = (form[name] ?: query[name])?.trim()?.ifEmpty { null }

    override fun allows(permission: String): Boolean = user.allows(permission)

    override fun link(params: Map<String, String>): String {
        if (params.isEmpty()) return "/$page"
        return "/$page" + params.entries.joinToString("&", prefix = "?") { (key, value) ->
            "${Html.encode(key)}=${Html.encode(value)}"
        }
    }

    fun link(vararg params: Pair<String, String>): String = link(params.toMap())
}
