package zone.vao.voxen

import java.nio.charset.StandardCharsets

/**
 * What a [PanelPage] sends back from [PanelPage.handle]. Use it when the
 * answer is not an HTML page in the panel frame: JSON, a text dump, a file to
 * download or a redirect.
 */
class PanelResponse private constructor(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val framed: Boolean,
    val fileName: String?,
    val location: String?,
) {

    companion object {

        private val BLOCKED_TYPES = listOf("text/html", "application/xhtml", "image/svg")

        /** An HTML body that Voxen wraps in the panel frame, exactly like [PanelPage.render]. */
        @JvmStatic
        fun page(html: String): PanelResponse =
            PanelResponse(200, HTML, html.toByteArray(StandardCharsets.UTF_8), true, null, null)

        /** A short message in the panel frame under the given HTTP status, for 403 or 404 pages. */
        @JvmStatic
        fun status(code: Int, message: String): PanelResponse =
            PanelResponse(code, HTML, PanelHtml.notice(message).toByteArray(StandardCharsets.UTF_8), true, null, null)

        /** A JSON body, served as it is. */
        @JvmStatic
        fun json(body: String): PanelResponse = raw("application/json; charset=utf-8", body)

        /** A plain text body, served as it is. */
        @JvmStatic
        fun text(body: String): PanelResponse = raw("text/plain; charset=utf-8", body)

        /**
         * Any other content type, served outside the panel frame. Markup types
         * are rejected because they would run in the panel's own origin.
         */
        @JvmStatic
        @JvmOverloads
        fun raw(contentType: String, body: ByteArray, status: Int = 200): PanelResponse {
            require(status in 200..599) { "Status $status is not a valid HTTP status." }
            return PanelResponse(status, checked(contentType), body, false, null, null)
        }

        @JvmStatic
        @JvmOverloads
        fun raw(contentType: String, body: String, status: Int = 200): PanelResponse =
            raw(contentType, body.toByteArray(StandardCharsets.UTF_8), status)

        /** A file the browser saves instead of showing, named [fileName]. */
        @JvmStatic
        fun download(fileName: String, contentType: String, body: ByteArray): PanelResponse {
            val name = fileName.filter { it.code in 32..126 && it !in FORBIDDEN_NAME }.take(120)
            require(name.isNotEmpty()) { "A download needs a file name." }
            return PanelResponse(200, checked(contentType), body, false, name, null)
        }

        /**
         * Sends the browser to another panel path. Only paths inside the panel
         * are allowed, so a page cannot bounce a logged in moderator elsewhere.
         */
        @JvmStatic
        fun redirect(location: String): PanelResponse {
            require(location.startsWith("/") && !location.startsWith("//")) {
                "A panel redirect has to be a path inside the panel, starting with a single '/'."
            }
            require(location.none { it == '\r' || it == '\n' }) { "A redirect target cannot contain line breaks." }
            return PanelResponse(303, HTML, ByteArray(0), false, null, location)
        }

        private fun checked(contentType: String): String {
            val lower = contentType.lowercase()
            require(BLOCKED_TYPES.none { lower.startsWith(it) }) {
                "Content type '$contentType' would run as markup in the panel; return PanelResponse.page instead."
            }
            require(lower.none { it == '\r' || it == '\n' }) { "A content type cannot contain line breaks." }
            return contentType
        }

        private const val HTML = "text/html; charset=utf-8"

        private val FORBIDDEN_NAME = setOf('"', '\\', '/')
    }
}
