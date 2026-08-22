package zone.vao.voxen.ticket

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import zone.vao.voxen.Voxen
import zone.vao.voxen.storage.TicketEntry
import java.time.Duration

@Suppress("UnstableApiUsage")
class TicketDialogs(private val plugin: Voxen) {

    fun panel(viewer: Player, entries: List<TicketEntry>) {
        val messages = plugin.messages()
        val service = plugin.ticketService
        val body =
            if (entries.isEmpty()) listOf(label(viewer, "dialog-tickets-empty"))
            else entries.take(PANEL_SIZE).mapIndexed { index, entry ->
                messages.line(viewer, "ticket-list-entry", *service.describe(viewer, index + 1, entry))
            }
        val buttons = entries.take(PANEL_SIZE).mapIndexed { index, entry ->
            button(
                messages.line(
                    viewer,
                    "dialog-ticket-open",
                    Placeholder.unparsed("index", (index + 1).toString()),
                    Placeholder.unparsed("status", messages.raw(viewer, "ticket-status-${entry.status.id}")),
                ),
                action(viewer) { service.openTicket(viewer, entry.id) },
            )
        }
        show(viewer, "dialog-tickets-title", entries.size.toString(), body, buttons, null)
    }

    fun view(viewer: Player, case: TicketService.Case) {
        val service = plugin.ticketService
        val body = service.conversation(viewer, case)
        val open = case.entry.status.active
        val buttons = buildList {
            if (open) {
                add(
                    button(label(viewer, "dialog-ticket-send"), input(viewer, "reply") { text ->
                        service.reply(viewer, case.entry.id, text)
                    })
                )
            }
            add(button(label(viewer, "dialog-back"), action(viewer) { service.openPanel(viewer) }))
        }
        val inputs = if (open) listOf(replyInput(viewer)) else null
        show(viewer, "dialog-ticket-title", case.entry.subject, body, buttons, inputs)
    }

    private fun show(
        viewer: Player,
        titleKey: String,
        value: String,
        body: List<Component>,
        buttons: List<ActionButton>,
        inputs: List<DialogInput>?,
    ) {
        viewer.showDialog(
            Dialog.create { factory ->
                factory.empty()
                    .base(
                        base(viewer, titleKey, value)
                            .body(body.takeLast(BODY_LINES).map { DialogBody.plainMessage(it, 320) })
                            .inputs(inputs.orEmpty())
                            .build()
                    )
                    .type(
                        if (buttons.isEmpty()) DialogType.notice()
                        else DialogType.multiAction(buttons).columns(1).build()
                    )
            }
        )
    }

    private fun base(viewer: Player, key: String, value: String): DialogBase.Builder =
        DialogBase.builder(
            plugin.messages().line(
                viewer,
                key,
                Placeholder.unparsed("subject", value),
                Placeholder.unparsed("amount", value),
            )
        )
            .canCloseWithEscape(true)
            .pause(false)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)

    private fun replyInput(viewer: Player): DialogInput =
        DialogInput.text("reply", label(viewer, "dialog-ticket-input"))
            .width(300)
            .maxLength(plugin.configManager.config.helpop.maxLength.coerceIn(16, 512))
            .build()

    private fun label(viewer: Player, key: String): Component = plugin.messages().line(viewer, key)

    private fun button(label: Component, action: DialogAction): ActionButton =
        ActionButton.builder(label).width(200).action(action).build()

    private fun input(viewer: Player, key: String, body: (String) -> Unit): DialogAction =
        action(viewer) { response -> body(response.getText(key).orEmpty().trim()) }

    private fun action(viewer: Player, body: (DialogResponseView) -> Unit): DialogAction =
        DialogAction.customClick({ response, _ ->
            plugin.threads.main {
                if (!viewer.hasPermission(PERMISSION)) {
                    plugin.messages().send(viewer, "no-permission")
                    return@main
                }
                body(response)
            }
        }, ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(10)).build())

    private companion object {
        const val PANEL_SIZE = 10
        const val BODY_LINES = 20
        const val PERMISSION = "voxen.helpop"
    }
}
