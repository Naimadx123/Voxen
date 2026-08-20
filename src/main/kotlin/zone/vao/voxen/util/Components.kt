package zone.vao.voxen.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.Style

object Components {

    private val EMPTY = Component.empty()
    private val PLACEHOLDERS = Regex("( ?)(%[^%\\s]+%|<prefix>|<suffix>)( ?)")

    fun stripEmptyPlaceholders(format: String, value: (String) -> String): String =
        PLACEHOLDERS.replace(format) { match ->
            val (left, token, right) = match.destructured
            val resolved = value(token)
            when {
                resolved.isNotBlank() -> left + resolved + right
                left.isNotEmpty() && right.isNotEmpty() -> " "
                else -> ""
            }
        }

    fun tidy(component: Component): Component {
        val leaves = ArrayList<Component>()
        flatten(component, Style.empty(), leaves)
        val kept = ArrayList<Component>(leaves.size)
        var dropNextGap = false
        for (leaf in leaves) {
            if (leaf === EMPTY) {
                if (kept.isNotEmpty() && blank(kept.last())) kept.removeAt(kept.size - 1) else dropNextGap = true
                continue
            }
            if (blank(leaf)) {
                if (dropNextGap || kept.isEmpty() || endsWithSpace(kept.last())) {
                    dropNextGap = false
                    continue
                }
                kept.add(leaf)
                continue
            }
            dropNextGap = false
            kept.add(leaf)
        }
        return when (kept.size) {
            0 -> Component.empty()
            1 -> kept[0]
            else -> Component.empty().children(kept)
        }
    }

    private fun flatten(component: Component, inherited: Style, out: MutableList<Component>) {
        if (empty(component)) {
            out.add(EMPTY)
            return
        }
        val style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)
        val own = (component as? TextComponent)?.content()
        if (own == null || own.isNotEmpty()) out.add(component.children(emptyList()).style(style))
        for (child in component.children()) flatten(child, style, out)
    }

    private fun empty(component: Component): Boolean {
        val text = (component as? TextComponent)?.content() ?: return false
        return text.isEmpty() && component.children().all { empty(it) }
    }

    private fun blank(component: Component): Boolean =
        (component as? TextComponent)?.content()?.isBlank() ?: false

    private fun endsWithSpace(component: Component): Boolean =
        (component as? TextComponent)?.content()?.lastOrNull()?.isWhitespace() ?: false
}
