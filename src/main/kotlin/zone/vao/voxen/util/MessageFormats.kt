package zone.vao.voxen.util

import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.Context
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

object MessageFormats {

    private val plain = PlainTextComponentSerializer.plainText()
    private val actions = setOf("open_url", "open_file", "run_command", "suggest_command", "copy_to_clipboard", "change_page")

    val miniMessage: MiniMessage = MiniMessage.builder()
        .editTags { it.resolver(TagResolver.resolver("click", ::click)) }
        .build()

    private fun click(arguments: ArgumentQueue, ctx: Context): Tag? {
        if (arguments.peek()?.lowerValue() !in actions) {
            return StandardTags.clickEvent().resolve("click", arguments, ctx)
        }
        val action = arguments.pop().lowerValue()
        val raw = arguments.popOr("A click value is required").value()
        val value = if ('<' in raw) plain.serialize(ctx.deserialize(raw)) else raw
        val event = when (action) {
            "open_url" -> ClickEvent.openUrl(value)
            "open_file" -> ClickEvent.openFile(value)
            "run_command" -> ClickEvent.runCommand(value)
            "suggest_command" -> ClickEvent.suggestCommand(value)
            "copy_to_clipboard" -> ClickEvent.copyToClipboard(value)
            else -> ClickEvent.changePage(value.toIntOrNull() ?: throw ctx.newException("Invalid page '$value'."))
        }
        return Tag.styling { it.clickEvent(event) }
    }
}
