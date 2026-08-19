package zone.vao.voxen.moderation

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import io.papermc.paper.dialog.DialogResponseView
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import zone.vao.voxen.Voxen
import zone.vao.voxen.command.NickCommand
import zone.vao.voxen.command.VoxenCommand
import zone.vao.voxen.config.ModeratorToolsConfig
import java.time.Duration

@Suppress("UnstableApiUsage")
class ModeratorDialogs(private val plugin: Voxen) {

    fun inspect(viewer: Player, target: ModeratorService.Target, lines: List<Component>) {
        val messages = plugin.messages()
        val canDelete = viewer.hasPermission("voxen.mod.delete")
        val buttons = buildList {
            if (viewer.hasPermission("voxen.mod.warn")) {
                add(button(label(viewer, "dialog-warn"), reasonAction(viewer, "voxen.mod.warn") { reason ->
                    plugin.moderatorService.warn(viewer, target, reason)
                }))
            }
            if (viewer.hasPermission("voxen.mod.mute")) {
                for (duration in listOf("10m", "1h", "1d")) {
                    add(
                        button(
                            messages.line(viewer, "dialog-mute", Placeholder.unparsed("duration", duration)),
                            reasonAction(viewer, "voxen.mod.mute") { reason ->
                                VoxenCommand.mute(plugin, viewer, target.name, duration, "all", reason)
                            },
                        )
                    )
                }
                add(button(label(viewer, "dialog-unmute"), run("voxen unmute ${target.name}")))
            }
            if (viewer.hasPermission("voxen.mod.notes")) {
                add(button(label(viewer, "dialog-note"), action(viewer, "voxen.mod.notes") {
                    plugin.moderatorService.notesPage(viewer, target)
                }))
            }
            if (viewer.hasPermission(NickCommand.PERMISSION_OTHERS) && plugin.configManager.config.nicknames.enabled) {
                add(button(label(viewer, "dialog-nick"), input(viewer, "nick", NickCommand.PERMISSION_OTHERS) { nick ->
                    val online = plugin.server.getPlayer(target.uuid)
                    if (online == null) {
                        plugin.messages().send(viewer, "player-not-found", Placeholder.unparsed("player", target.name))
                    } else {
                        NickCommand.apply(plugin, viewer, online, nick.ifEmpty { "reset" })
                    }
                }))
            }
            if (viewer.hasPermission("voxen.mod.history")) {
                add(button(label(viewer, "dialog-history"), run("voxen history ${target.name}")))
            }
            if (canDelete) {
                add(
                    button(label(viewer, "dialog-delete"), action(viewer, "voxen.mod.delete") { response ->
                        val amount = response.getFloat("amount")?.toInt() ?: 1
                        plugin.moderatorService.deleteMessages(viewer, target, amount.coerceIn(1, 100))
                    })
                )
            }
            for (custom in plugin.configManager.config.moderatorTools.dialogButtons) {
                if (custom.permission != null && !viewer.hasPermission(custom.permission)) continue
                add(customButton(viewer, target, custom))
            }
        }
        viewer.showDialog(
            Dialog.create { factory ->
                factory.empty()
                    .base(
                        base(viewer, "dialog-inspect-title", target)
                            .body(lines.map { DialogBody.plainMessage(it, 320) })
                            .inputs(
                                buildList {
                                    add(reasonInput(viewer))
                                    add(nickInput(viewer))
                                    if (canDelete) add(amountInput(viewer))
                                }
                            )
                            .build()
                    )
                    .type(DialogType.multiAction(buttons).columns(2).build())
            }
        )
    }

    fun notes(viewer: Player, target: ModeratorService.Target, lines: List<Component>) {
        val service = plugin.moderatorService
        val body =
            if (lines.isEmpty()) listOf(label(viewer, "dialog-notes-empty"))
            else lines
        val buttons = buildList {
            add(button(label(viewer, "dialog-note-add"), input(viewer, "note", "voxen.mod.notes") { text ->
                if (text.isEmpty()) service.notesPage(viewer, target)
                else service.addNote(viewer, target, text) { service.notesPage(viewer, target) }
            }))
            // ponytail: first 10 notes get a delete button, paging when someone collects more
            for (index in 1..minOf(lines.size, 10)) {
                add(
                    button(
                        plugin.messages().line(viewer, "dialog-note-delete", Placeholder.unparsed("index", index.toString())),
                        action(viewer, "voxen.mod.notes") {
                            service.deleteNote(viewer, target, index) { service.notesPage(viewer, target) }
                        },
                    )
                )
            }
            add(button(label(viewer, "dialog-back"), run("voxen inspect ${target.name}")))
        }
        viewer.showDialog(
            Dialog.create { factory ->
                factory.empty()
                    .base(
                        base(viewer, "dialog-notes-title", target)
                            .body(body.map { DialogBody.plainMessage(it, 320) })
                            .inputs(listOf(noteInput(viewer)))
                            .build()
                    )
                    .type(DialogType.multiAction(buttons).columns(2).build())
            }
        )
    }

    fun warn(viewer: Player, target: ModeratorService.Target) {
        viewer.showDialog(
            Dialog.create { factory ->
                factory.empty()
                    .base(
                        base(viewer, "dialog-warn-title", target)
                            .body(
                                listOf(
                                    DialogBody.plainMessage(
                                        plugin.messages().line(
                                            viewer,
                                            "dialog-warn-body",
                                            Placeholder.unparsed("player", target.name),
                                        ),
                                        320,
                                    )
                                )
                            )
                            .inputs(listOf(reasonInput(viewer)))
                            .build()
                    )
                    .type(
                        DialogType.confirmation(
                            button(label(viewer, "dialog-confirm"), reasonAction(viewer, "voxen.mod.warn") { reason ->
                                plugin.moderatorService.warn(viewer, target, reason)
                            }),
                            ActionButton.builder(label(viewer, "dialog-cancel")).width(150).build(),
                        )
                    )
            }
        )
    }

    private fun base(viewer: Player, key: String, target: ModeratorService.Target): DialogBase.Builder =
        DialogBase.builder(plugin.messages().line(viewer, key, Placeholder.unparsed("player", target.name)))
            .canCloseWithEscape(true)
            .pause(false)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)

    private fun reasonInput(viewer: Player): DialogInput =
        DialogInput.text("reason", label(viewer, "dialog-reason"))
            .width(300)
            .maxLength(200)
            .build()

    private fun noteInput(viewer: Player): DialogInput =
        DialogInput.text("note", label(viewer, "dialog-note-input"))
            .width(300)
            .maxLength(200)
            .build()

    private fun amountInput(viewer: Player): DialogInput =
        DialogInput.numberRange("amount", label(viewer, "dialog-delete-amount"), 1f, deleteMax())
            .step(1f)
            .initial(minOf(5f, deleteMax()))
            .width(300)
            .build()

    private fun deleteMax(): Float =
        plugin.configManager.config.moderatorTools.deleteKeep.coerceIn(2, 100).toFloat()

    private fun customButton(
        viewer: Player,
        target: ModeratorService.Target,
        entry: ModeratorToolsConfig.DialogButton,
    ): ActionButton {
        val command = entry.command
            .replace("<player>", target.name)
            .replace("<uuid>", target.uuid.toString())
            .replace("<moderator>", viewer.name)
        return button(MiniMessage.miniMessage().deserialize(entry.label), action(viewer, entry.permission) {
            val executor = if (entry.console) plugin.server.consoleSender else viewer
            runCatching { plugin.server.dispatchCommand(executor, command) }
                .onFailure { plugin.logger.warning("Dialog button command '$command' failed: ${it.message}") }
        })
    }

    private fun nickInput(viewer: Player): DialogInput =
        DialogInput.text("nick", label(viewer, "dialog-nick-input"))
            .width(300)
            .maxLength(64)
            .build()

    private fun label(viewer: Player, key: String): Component = plugin.messages().line(viewer, key)

    private fun button(label: Component, action: DialogAction): ActionButton =
        ActionButton.builder(label).width(150).action(action).build()

    private fun run(command: String): DialogAction =
        DialogAction.staticAction(ClickEvent.runCommand("/$command"))

    private fun reasonAction(viewer: Player, permission: String, body: (String) -> Unit): DialogAction =
        input(viewer, "reason", permission, body)

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
}
