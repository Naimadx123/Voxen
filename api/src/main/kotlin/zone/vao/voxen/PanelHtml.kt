package zone.vao.voxen

/** Helpers for the HTML a [PanelPage] returns. */
object PanelHtml {

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
}
