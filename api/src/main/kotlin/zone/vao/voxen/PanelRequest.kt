package zone.vao.voxen

/** One panel request: who is asking, what they sent and what they may see. */
interface PanelRequest {

    /** Id of the page the request belongs to. */
    val page: String

    /** Name of the panel account, as configured in `modules/web.yml`. */
    val account: String

    /** CSRF token; put it in a hidden `token` field or the form is rejected. */
    val token: String

    /** Reads a posted field, falling back to the query string. Null when absent or empty. */
    fun param(name: String): String?

    /** Returns true when the account holds the given panel permission. */
    fun allows(permission: String): Boolean

    /** Builds a URL back to this page carrying the given query parameters. */
    fun link(params: Map<String, String>): String
}
