package zone.vao.voxen.report

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
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import zone.vao.voxen.Voxen
import zone.vao.voxen.config.ReportsConfig
import zone.vao.voxen.moderation.ModeratorService
import zone.vao.voxen.storage.ReportEntry
import java.time.Duration

@Suppress("UnstableApiUsage")
class ReportDialogs(private val plugin: Voxen) {

    fun submit(viewer: Player, target: ModeratorService.Target, reference: MessageIndex.Entry?) {
        val settings = plugin.configManager.config.reports
        val messages = plugin.messages()
        val buttons = buildList {
            for (reason in settings.reasons) {
                if (reason.permission != null && !viewer.hasPermission(reason.permission)) continue
                add(
                    button(MiniMessage.miniMessage().deserialize(reason.label), action(viewer, ReportService.PERMISSION) {
                        plugin.reportService.submit(viewer, target, reason.id, reference)
                    })
                )
            }
            if (settings.freeText) {
                add(
                    button(label(viewer, "dialog-report-send"), input(viewer, "reason", ReportService.PERMISSION) { text ->
                        if (text.isEmpty()) messages.send(viewer, "report-needs-reason")
                        else plugin.reportService.submit(viewer, target, text, reference)
                    })
                )
            }
        }
        val body = buildList {
            add(messages.line(viewer, "dialog-report-body", Placeholder.unparsed("player", target.name)))
            if (reference != null) {
                add(
                    messages.line(
                        viewer,
                        "dialog-report-message",
                        Placeholder.unparsed("player", reference.authorName),
                        Placeholder.unparsed("message", reference.content),
                    )
                )
            }
        }
        show(viewer, "dialog-report-title", target.name, body, buttons, listOf(reasonInput(viewer)).takeIf { settings.freeText })
    }

    fun queue(viewer: Player, entries: List<ReportEntry>) {
        val messages = plugin.messages()
        val body =
            if (entries.isEmpty()) listOf(label(viewer, "dialog-reports-empty"))
            else entries.take(QUEUE_SIZE).mapIndexed { index, entry ->
                messages.line(viewer, "report-list-entry", *plugin.reportService.describe(viewer, index + 1, entry))
            }
        val buttons = entries.take(QUEUE_SIZE).mapIndexed { index, entry ->
            button(
                messages.line(
                    viewer,
                    "dialog-report-open",
                    Placeholder.unparsed("index", (index + 1).toString()),
                    Placeholder.unparsed("player", entry.targetName),
                ),
                action(viewer, ReportService.MANAGE) { plugin.reportService.openReport(viewer, entry.id) },
            )
        }
        show(viewer, "dialog-reports-title", entries.size.toString(), body, buttons, null)
    }

    fun view(viewer: Player, case: ReportService.Case) {
        val service = plugin.reportService
        val entry = case.entry
        val settings = plugin.configManager.config.reports
        val body = service.fieldLines(viewer, entry) + service.contextLines(viewer, case)
        val reopen = { service.openReport(viewer, entry.id) }
        val buttons = buildList {
            for (choice in ReportService.Action.entries) {
                if (choice == ReportService.Action.DELETE || choice.status == entry.status) continue
                add(
                    button(label(viewer, "dialog-report-${choice.id}"), action(viewer, ReportService.MANAGE) {
                        service.act(viewer, entry.id, choice) { service.openQueue(viewer) }
                    })
                )
            }
            if (viewer.hasPermission(WARN)) {
                add(button(label(viewer, "dialog-report-warn"), action(viewer, WARN) { service.warn(viewer, entry, reopen) }))
            }
            if (viewer.hasPermission(MUTE)) {
                for (duration in settings.muteDurations) {
                    add(
                        button(
                            plugin.messages().line(viewer, "dialog-mute", Placeholder.unparsed("duration", duration)),
                            action(viewer, MUTE) { service.mute(viewer, entry, duration, reopen) },
                        )
                    )
                }
            }
            if (viewer.hasPermission(DELETE) && entry.messageId != null) {
                add(
                    button(label(viewer, "dialog-report-message-delete"), action(viewer, DELETE) {
                        service.deleteMessage(viewer, entry, reopen)
                    })
                )
            }
            for (custom in settings.actions) {
                if (custom.permission != null && !viewer.hasPermission(custom.permission)) continue
                add(customButton(viewer, entry, custom))
            }
            add(
                button(label(viewer, "dialog-report-history"), action(viewer, ReportService.MANAGE) {
                    service.openHistory(viewer, entry.id)
                })
            )
            add(button(label(viewer, "dialog-back"), action(viewer, ReportService.MANAGE) { service.openQueue(viewer) }))
        }
        show(viewer, "dialog-report-view-title", entry.targetName, body, buttons, null)
    }

    fun history(viewer: Player, case: ReportService.Case) {
        val lines = plugin.reportService.historyLines(viewer, case)
        val body = lines.ifEmpty { listOf(label(viewer, "dialog-report-history-empty")) }
        val buttons = listOf(
            button(label(viewer, "dialog-back"), action(viewer, ReportService.MANAGE) {
                plugin.reportService.openReport(viewer, case.entry.id)
            })
        )
        show(viewer, "dialog-report-history-title", case.entry.targetName, body, buttons, null)
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
                            .body(body.take(BODY_LINES).map { DialogBody.plainMessage(it, 320) })
                            .inputs(inputs.orEmpty())
                            .build()
                    )
                    .type(
                        if (buttons.isEmpty()) DialogType.notice()
                        else DialogType.multiAction(buttons).columns(2).build()
                    )
            }
        )
    }

    private fun customButton(viewer: Player, entry: ReportEntry, custom: ReportsConfig.Action): ActionButton =
        button(MiniMessage.miniMessage().deserialize(custom.label), action(viewer, custom.permission) {
            plugin.reportService.run(viewer, entry, custom)
        })

    private fun base(viewer: Player, key: String, value: String): DialogBase.Builder =
        DialogBase.builder(
            plugin.messages().line(
                viewer,
                key,
                Placeholder.unparsed("player", value),
                Placeholder.unparsed("amount", value),
            )
        )
            .canCloseWithEscape(true)
            .pause(false)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)

    private fun reasonInput(viewer: Player): DialogInput =
        DialogInput.text("reason", label(viewer, "dialog-report-input"))
            .width(300)
            .maxLength(plugin.configManager.config.reports.maxLength.coerceIn(16, 512))
            .build()

    private fun label(viewer: Player, key: String): Component = plugin.messages().line(viewer, key)

    private fun button(label: Component, action: DialogAction): ActionButton =
        ActionButton.builder(label).width(150).action(action).build()

    private fun input(viewer: Player, key: String, permission: String, body: (String) -> Unit): DialogAction =
        action(viewer, permission) { response -> body(response.getText(key).orEmpty().trim()) }

    private fun action(viewer: Player, permission: String?, body: (DialogResponseView) -> Unit): DialogAction =
        DialogAction.customClick({ response, _ ->
            plugin.threads.main {
                if (permission != null && !viewer.hasPermission(permission)) {
                    plugin.messages().send(viewer, "no-permission")
                    return@main
                }
                body(response)
            }
        }, ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(10)).build())

    private companion object {
        const val QUEUE_SIZE = 10
        const val BODY_LINES = 24
        const val WARN = "voxen.mod.warn"
        const val MUTE = "voxen.mod.mute"
        const val DELETE = "voxen.mod.delete"
    }
}
