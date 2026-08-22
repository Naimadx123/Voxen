package zone.vao.voxen

/**
 * What Voxen's chat filter thinks of a piece of text.
 *
 * [content] is the text to use from here on: unchanged when it is clean or
 * blocked, and with the offending parts replaced when it was censored.
 */
data class FilterResult(
    val verdict: Verdict,
    val content: String,
) {

    /** True when the text may be shown as it is. */
    fun isClean(): Boolean = verdict == Verdict.CLEAN

    /** True when the text should not be shown at all. */
    fun isBlocked(): Boolean = verdict == Verdict.BLOCKED

    enum class Verdict {
        /** Nothing matched. */
        CLEAN,

        /** Something matched and the filter is set to refuse the whole message. */
        BLOCKED,

        /** Something matched and was replaced; see [content]. */
        CENSORED,
    }
}
