package zone.vao.voxen

/**
 * Helpers for the HTML a [PanelPage] returns. The builders emit the same
 * markup as the bundled pages, so a page written with them follows the panel
 * theme and keeps following it when the theme changes.
 */
object PanelHtml {

    /** How a [tag] is coloured. */
    enum class Style(internal val css: String) {
        NEUTRAL(""),
        INFO("info"),
        GOOD("good"),
        WARN("warn"),
        BAD("bad"),
    }

    /** One cell of a [table]. Use [Cell.of] for text and [Cell.html] for markup you built yourself. */
    class Cell private constructor(internal val html: String) {
        companion object {
            /** A cell holding plain text, escaped for you. */
            @JvmStatic
            fun of(text: String): Cell = Cell(escape(text))

            /** A cell holding markup, for example a [tag] or a [link]. Nothing is escaped. */
            @JvmStatic
            fun html(markup: String): Cell = Cell(markup)
        }
    }

    /** One entry in a [tabs] row. */
    class Tab @JvmOverloads constructor(
        val label: String,
        val href: String,
        val active: Boolean = false,
    )

    /**
     * Escapes text so it cannot break out of the surrounding HTML. Use it on
     * every player name, message and database value the page prints.
     */
    @JvmStatic
    fun escape(value: String): String = buildString(value.length) {
        for (char in value) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    /** The page title, printed once at the top. */
    @JvmStatic
    fun heading(text: String): String = "<h1>${escape(text)}</h1>"

    /** A smaller heading that separates sections. */
    @JvmStatic
    fun section(text: String): String = "<h2>${escape(text)}</h2>"

    /** Wraps markup in the panel's rounded surface. */
    @JvmStatic
    fun card(body: String): String = "<div class=\"card\">$body</div>"

    /** A centred message inside a card, for empty lists and errors. */
    @JvmStatic
    fun notice(text: String): String = "<div class=\"card\"><p class=\"empty\">${escape(text)}</p></div>"

    /** Small grey text, for hints under a table or a form. */
    @JvmStatic
    fun muted(text: String): String = "<p class=\"muted\">${escape(text)}</p>"

    /** A coloured pill, for a status or a count. */
    @JvmStatic
    @JvmOverloads
    fun tag(text: String, style: Style = Style.NEUTRAL): String {
        val css = if (style.css.isEmpty()) "tag" else "tag ${style.css}"
        return "<span class=\"$css\">${escape(text)}</span>"
    }

    /** A link inside the panel. */
    @JvmStatic
    fun link(label: String, href: String): String = "<a href=\"${escape(href)}\">${escape(label)}</a>"

    /** A row of filter links above the content. */
    @JvmStatic
    fun tabs(tabs: List<Tab>): String = tabs.joinToString(
        separator = "",
        prefix = "<div class=\"tabs\">",
        postfix = "</div>",
    ) { tab ->
        val active = if (tab.active) " class=\"active\"" else ""
        "<a href=\"${escape(tab.href)}\"$active>${escape(tab.label)}</a>"
    }

    /** A table inside a card. Rows shorter than [headers] are padded with empty cells. */
    @JvmStatic
    fun table(headers: List<String>, rows: List<List<Cell>>): String {
        if (rows.isEmpty()) return notice("Nothing here yet.")
        val head = headers.joinToString("") { "<th>${escape(it)}</th>" }
        val body = rows.joinToString("") { row ->
            val cells = (0 until headers.size.coerceAtLeast(row.size)).joinToString("") { index ->
                "<td>${row.getOrNull(index)?.html.orEmpty()}</td>"
            }
            "<tr>$cells</tr>"
        }
        return card("<table><thead><tr>$head</tr></thead><tbody>$body</tbody></table>")
    }

    /**
     * A form that posts back to the page. The CSRF token is written for you,
     * so a form built here is never rejected as expired.
     */
    @JvmStatic
    @JvmOverloads
    fun form(request: PanelRequest, body: String, action: String? = null): String =
        "<form class=\"actions\" method=\"post\" action=\"${escape(action ?: request.link(emptyMap()))}\">" +
            "<input type=\"hidden\" name=\"token\" value=\"${escape(request.token)}\">$body</form>"

    /** A submit button. [name] and [value] are what [PanelRequest.param] reads back. */
    @JvmStatic
    fun button(name: String, value: String, label: String): String =
        "<button type=\"submit\" name=\"${escape(name)}\" value=\"${escape(value)}\">${escape(label)}</button>"

    /** A single line text field, sized to fill the row it sits in. */
    @JvmStatic
    @JvmOverloads
    fun field(name: String, placeholder: String = "", value: String = ""): String =
        "<input class=\"reply\" type=\"text\" name=\"${escape(name)}\" " +
            "placeholder=\"${escape(placeholder)}\" value=\"${escape(value)}\">"
}
