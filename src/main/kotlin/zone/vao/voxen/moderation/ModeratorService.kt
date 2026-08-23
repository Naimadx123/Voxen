package zone.vao.voxen.moderation

import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.event.ChatMessageDeleteEvent
import zone.vao.voxen.event.PlayerWarnEvent
import zone.vao.voxen.presence.PresenceService
import zone.vao.voxen.storage.PlayerDataService
import zone.vao.voxen.storage.StaffNote
import zone.vao.voxen.util.Pages
import zone.vao.voxen.util.Threads
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class ModeratorService(
    private val server: Server,
    private val config: () -> VoxenConfig,
    private val playerData: PlayerDataService,
    private val mutes: MuteService,
    private val presence: PresenceService,
    private val threads: Threads,
) : Listener {

    private val recent = ArrayDeque<Pair<UUID, SignedMessage>>()

    var dialogs: ((Player, Target, List<Component>) -> Unit)? = null
    var warnDialog: ((Player, Target) -> Unit)? = null
    var notesDialog: ((Player, Target, List<Component>) -> Unit)? = null

    data class Target(val uuid: UUID, val name: String)

    fun warn(sender: CommandSender, target: Target, reason: String?) {
        val settings = config().moderatorTools
        val messages = config().messages
        if (!enabled(sender) || !settings.warningsEnabled) {
            if (settings.enabled) messages.send(sender, "warn-disabled")
            return
        }
        val text = reason?.trim()?.ifEmpty { null }
        if (text == null) {
            val viewer = sender as? Player
            if (viewer != null && settings.dialogs) warnDialog?.invoke(viewer, target)
            else messages.send(sender, "warn-needs-reason")
            return
        }
        apply(sender, sender.name, target, text)
    }

    fun warn(actor: String, target: Target, reason: String): Boolean {
        val settings = config().moderatorTools
        if (!settings.enabled || !settings.warningsEnabled) return false
        val text = reason.trim().ifEmpty { return false }
        return apply(null, actor, target, text)
    }

    private fun apply(sender: CommandSender?, actor: String, target: Target, reason: String): Boolean {
        val settings = config().moderatorTools
        val messages = config().messages
        val event = PlayerWarnEvent(target.uuid, target.name, reason, actor)
        server.pluginManager.callEvent(event)
        if (event.isCancelled) return false
        val text = event.reason.trim().ifEmpty { return false }
        val note = StaffNote(
            id = UUID.randomUUID(),
            target = target.uuid,
            targetName = target.name,
            author = actor,
            content = text,
            kind = StaffNote.Kind.WARN,
            createdAt = System.currentTimeMillis(),
        )
        playerData.durable({ storage ->
            storage.saveStaffNote(note)
            val count = storage.staffNotes(target.uuid, StaffNote.Kind.WARN, settings.warningCutoff).size
            threads.main {
                sender?.let {
                    messages.send(
                        it,
                        "warned-player",
                        Placeholder.unparsed("player", target.name),
                        Placeholder.unparsed("reason", text),
                        Placeholder.unparsed("amount", count.toString()),
                    )
                }
                val online = server.getPlayer(target.uuid)
                if (settings.notifyTarget && online != null) {
                    threads.forPlayer(online) {
                        messages.send(
                            online,
                            "warn-received",
                            Placeholder.unparsed("reason", text),
                            Placeholder.unparsed("amount", count.toString()),
                        )
                    }
                }
                alert(
                    "warn-alert",
                    Placeholder.unparsed("player", target.name),
                    Placeholder.unparsed("moderator", actor),
                    Placeholder.unparsed("reason", text),
                    Placeholder.unparsed("amount", count.toString()),
                )
                runRules(actor, target, count, text)
            }
        }, { threads.main { sender?.let { messages.send(it, "storage-busy") } } })
        return true
    }

    fun warnings(sender: CommandSender, target: Target, page: Int = 1) {
        val settings = config().moderatorTools
        val messages = config().messages
        if (!enabled(sender) || !settings.warningsEnabled) {
            if (settings.enabled) messages.send(sender, "warn-disabled")
            return
        }
        list(sender, target, StaffNote.Kind.WARN, "warn-list-header", "warn-list-entry", "warn-list-empty", page, "warns")
    }

    fun unwarn(sender: CommandSender, target: Target, index: Int) {
        val settings = config().moderatorTools
        if (!enabled(sender) || !settings.warningsEnabled) {
            if (settings.enabled) config().messages.send(sender, "warn-disabled")
            return
        }
        remove(sender, target, StaffNote.Kind.WARN, index, "warn-removed", "warn-invalid-index")
    }

    fun notes(sender: CommandSender, target: Target, page: Int = 1) {
        if (!enabled(sender) || !notesEnabled(sender)) return
        list(sender, target, StaffNote.Kind.NOTE, "note-list-header", "note-list-entry", "note-list-empty", page, "notes")
    }

    fun notesPage(viewer: Player, target: Target) {
        if (!enabled(viewer) || !notesEnabled(viewer)) return
        val messages = config().messages
        playerData.async { storage ->
            val entries = storage.staffNotes(target.uuid, StaffNote.Kind.NOTE, 0L)
            threads.forPlayer(viewer) {
                val lines = entries.mapIndexed { index, entry ->
                    messages.line(
                        viewer,
                        "note-list-entry",
                        Placeholder.unparsed("index", (index + 1).toString()),
                        Placeholder.unparsed("time", TIME_FORMAT.format(Instant.ofEpochMilli(entry.createdAt))),
                        Placeholder.unparsed("moderator", entry.author),
                        Placeholder.unparsed("reason", entry.content),
                    )
                }
                notesDialog?.invoke(viewer, target, lines)
            }
        }
    }

    fun addNote(sender: CommandSender, target: Target, content: String, then: (() -> Unit)? = null) {
        if (!enabled(sender) || !notesEnabled(sender)) return
        val messages = config().messages
        val text = content.trim().ifEmpty {
            messages.send(sender, "note-needs-text")
            return
        }
        val note = StaffNote(
            id = UUID.randomUUID(),
            target = target.uuid,
            targetName = target.name,
            author = sender.name,
            content = text,
            kind = StaffNote.Kind.NOTE,
            createdAt = System.currentTimeMillis(),
        )
        playerData.durable({ storage ->
            storage.saveStaffNote(note)
            threads.main {
                messages.send(sender, "note-added", Placeholder.unparsed("player", target.name))
                then?.invoke()
            }
        }, { threads.main { messages.send(sender, "storage-busy") } })
    }

    fun deleteNote(sender: CommandSender, target: Target, index: Int, then: (() -> Unit)? = null) {
        if (!enabled(sender) || !notesEnabled(sender)) return
        remove(sender, target, StaffNote.Kind.NOTE, index, "note-removed", "note-invalid-index", then)
    }

    fun inspect(sender: CommandSender, target: Target) {
        val settings = config().moderatorTools
        val messages = config().messages
        if (!enabled(sender)) return
        val online = server.getPlayer(target.uuid)
        val where = when {
            online != null -> config().network.serverId
            else -> presence.find(target.name)?.server ?: messages.raw(sender, "inspect-offline")
        }
        val active = mutes.mutesFor(target.uuid)
        playerData.async { storage ->
            val warns =
                if (settings.warningsEnabled) storage.staffNoteCount(target.uuid, StaffNote.Kind.WARN, settings.warningCutoff)
                else 0
            val notes = if (settings.notesEnabled) storage.staffNoteCount(target.uuid, StaffNote.Kind.NOTE, 0L) else 0
            val channel = playerData.cached(target.uuid)?.activeChannel ?: storage.loadPlayer(target.uuid)?.activeChannel
            threads.main {
                val lines = listOf(
                    "server" to where,
                    "channel" to (channel ?: "-"),
                    "mutes" to active.joinToString(", ") { it.channel ?: "all" }.ifEmpty { "-" },
                    "warnings" to warns.toString(),
                    "notes" to notes.toString(),
                ).map { (key, value) ->
                    messages.line(
                        sender,
                        "inspect-line",
                        Placeholder.unparsed("key", key),
                        Placeholder.unparsed("value", value),
                    )
                }
                messages.send(sender, "inspect-header", Placeholder.unparsed("player", target.name))
                for (line in lines) sender.sendMessage(line)
                val viewer = sender as? Player
                if (viewer != null && settings.dialogs) dialogs?.invoke(viewer, target, lines)
            }
        }
    }

    fun remember(player: Player, signed: SignedMessage) {
        val settings = config().moderatorTools
        if (!settings.enabled || !settings.deleteEnabled || !signed.canDelete()) return
        synchronized(recent) {
            recent.addLast(player.uniqueId to signed)
            while (recent.size > settings.deleteKeep) recent.removeFirst()
        }
    }

    fun deleteMessages(sender: CommandSender, target: Target, amount: Int) {
        val settings = config().moderatorTools
        val messages = config().messages
        if (!enabled(sender)) return
        if (!settings.deleteEnabled) {
            messages.send(sender, "delete-disabled")
            return
        }
        if (server.getPlayer(target.uuid)?.hasPermission(DELETE_EXEMPT) == true) {
            messages.send(sender, "delete-exempt", Placeholder.unparsed("player", target.name))
            return
        }
        val picked = synchronized(recent) {
            val owned = recent.filter { it.first == target.uuid }.takeLast(amount.coerceAtLeast(1))
            recent.removeAll(owned.toSet())
            owned.map { it.second }
        }
        if (picked.isEmpty()) {
            messages.send(sender, "delete-none", Placeholder.unparsed("player", target.name))
            return
        }
        broadcastDelete(picked)
        announceDelete(target, sender.name, picked, null)
        messages.send(
            sender,
            "deleted-messages",
            Placeholder.unparsed("player", target.name),
            Placeholder.unparsed("amount", picked.size.toString()),
        )
    }

    fun deleteSigned(
        sender: CommandSender,
        target: Target,
        signed: SignedMessage,
        actor: String = sender.name,
        messageId: UUID? = null,
    ): Boolean {
        val settings = config().moderatorTools
        val messages = config().messages
        if (!enabled(sender)) return false
        if (!settings.deleteEnabled) {
            messages.send(sender, "delete-disabled")
            return false
        }
        if (server.getPlayer(target.uuid)?.hasPermission(DELETE_EXEMPT) == true) {
            messages.send(sender, "delete-exempt", Placeholder.unparsed("player", target.name))
            return false
        }
        synchronized(recent) { recent.removeAll { it.second == signed } }
        broadcastDelete(listOf(signed))
        announceDelete(target, actor, listOf(signed), messageId)
        messages.send(
            sender,
            "deleted-messages",
            Placeholder.unparsed("player", target.name),
            Placeholder.unparsed("amount", "1"),
        )
        return true
    }

    fun chatButtons(sender: Player, signed: SignedMessage, viewer: Player): Component? {
        if (!config().moderatorTools.enabled) return null
        val delete = deleteButton(sender, signed, viewer)
        val manage = manageButton(sender, viewer)
        return when {
            delete == null -> manage
            manage == null -> delete
            else -> Component.empty().append(delete).append(manage)
        }
    }

    private fun manageButton(sender: Player, viewer: Player): Component? {
        if (!config().moderatorTools.manageButton || !viewer.hasPermission(INSPECT)) return null
        return config().messages.line(viewer, "manage-button", Placeholder.unparsed("player", sender.name))
            .clickEvent(ClickEvent.runCommand("/voxen inspect ${sender.name}"))
    }

    private fun deleteButton(sender: Player, signed: SignedMessage, viewer: Player): Component? {
        val settings = config().moderatorTools
        if (!settings.deleteEnabled || !settings.deleteButton) return null
        if (!signed.canDelete()) return null
        if (!viewer.hasPermission(DELETE) || sender.hasPermission(DELETE_EXEMPT)) return null
        val messages = config().messages
        val name = sender.name
        return messages.line(viewer, "delete-button", Placeholder.unparsed("player", name))
            .clickEvent(
                ClickEvent.callback(
                    {
                        threads.main {
                            if (!viewer.hasPermission(DELETE) || !config().moderatorTools.deleteEnabled) {
                                messages.send(viewer, "no-permission")
                                return@main
                            }
                            synchronized(recent) { recent.removeAll { it.second == signed } }
                            broadcastDelete(listOf(signed))
                            messages.send(
                                viewer,
                                "deleted-messages",
                                Placeholder.unparsed("player", name),
                                Placeholder.unparsed("amount", "1"),
                            )
                        }
                    },
                    ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(15)).build(),
                )
            )
    }

    private fun announceDelete(target: Target, actor: String, picked: List<SignedMessage>, messageId: UUID?) {
        for (signed in picked) {
            server.pluginManager.callEvent(
                ChatMessageDeleteEvent(target.uuid, target.name, actor, signed.message(), messageId)
            )
        }
    }

    private fun broadcastDelete(picked: List<SignedMessage>) {
        for (viewer in server.onlinePlayers) {
            threads.forPlayer(viewer) { for (signed in picked) viewer.deleteMessage(signed) }
        }
    }

    fun clear() {
        synchronized(recent) { recent.clear() }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val settings = config().moderatorTools
        if (!settings.enabled || !settings.joinAlerts) return
        val player = event.player
        val muteCount = mutes.mutesFor(player.uniqueId).size
        playerData.async { storage ->
            val warns =
                if (settings.warningsEnabled) storage.staffNotes(player.uniqueId, StaffNote.Kind.WARN, settings.warningCutoff).size
                else 0
            if (muteCount == 0 && warns == 0) return@async
            threads.main {
                alert(
                    "alert-join",
                    Placeholder.unparsed("player", player.name),
                    Placeholder.unparsed("mutes", muteCount.toString()),
                    Placeholder.unparsed("amount", warns.toString()),
                )
            }
        }
    }

    private fun runRules(actor: String, target: Target, count: Int, reason: String) {
        val rules = config().moderatorTools.warningRules.filter { it.matches(count) }
        if (rules.isEmpty()) return
        val commands = rules.flatMap { it.commands }.map { command ->
            command.replace("<player>", target.name)
                .replace("<uuid>", target.uuid.toString())
                .replace("<count>", count.toString())
                .replace("<moderator>", actor)
                .replace("<reason>", reason)
        }
        threads.main {
            for (command in commands) {
                runCatching { server.dispatchCommand(server.consoleSender, command) }
                    .onFailure { server.logger.warning("Warning rule command '$command' failed: ${it.message}") }
            }
        }
        alert(
            "warn-rule",
            Placeholder.unparsed("player", target.name),
            Placeholder.unparsed("amount", count.toString()),
        )
    }

    private fun list(
        sender: CommandSender,
        target: Target,
        kind: StaffNote.Kind,
        headerKey: String,
        entryKey: String,
        emptyKey: String,
        page: Int,
        command: String,
    ) {
        val messages = config().messages
        val since = if (kind == StaffNote.Kind.WARN) config().moderatorTools.warningCutoff else 0L
        playerData.async { storage ->
            val entries = storage.staffNotes(target.uuid, kind, since)
            threads.main {
                if (entries.isEmpty()) {
                    messages.send(sender, emptyKey, Placeholder.unparsed("player", target.name))
                    return@main
                }
                messages.send(
                    sender,
                    headerKey,
                    Placeholder.unparsed("player", target.name),
                    Placeholder.unparsed("amount", entries.size.toString()),
                )
                val view = Pages.of(entries, page)
                view.items.forEachIndexed { offsetIndex, entry ->
                    val index = view.offset + offsetIndex
                    sender.sendMessage(
                        messages.line(
                            sender,
                            entryKey,
                            Placeholder.unparsed("index", (index + 1).toString()),
                            Placeholder.unparsed("time", TIME_FORMAT.format(Instant.ofEpochMilli(entry.createdAt))),
                            Placeholder.unparsed("moderator", entry.author),
                            Placeholder.unparsed("reason", entry.content),
                        )
                    )
                }
                if (view.hasNext) {
                    sender.sendMessage(
                        messages.line(
                            sender,
                            "page-footer",
                            Placeholder.unparsed("page", view.number.toString()),
                            Placeholder.unparsed("pages", view.count.toString()),
                            Placeholder.unparsed("command", "/voxen $command ${target.name} ${view.number + 1}"),
                        )
                    )
                }
            }
        }
    }

    private fun remove(
        sender: CommandSender,
        target: Target,
        kind: StaffNote.Kind,
        index: Int,
        okKey: String,
        missingKey: String,
        then: (() -> Unit)? = null,
    ) {
        val messages = config().messages
        val since = if (kind == StaffNote.Kind.WARN) config().moderatorTools.warningCutoff else 0L
        playerData.async { storage ->
            val entries = storage.staffNotes(target.uuid, kind, since)
            val entry = entries.getOrNull(index - 1)
            val removed = entry != null && storage.deleteStaffNote(entry.id)
            threads.main {
                messages.send(
                    sender,
                    if (removed) okKey else missingKey,
                    Placeholder.unparsed("player", target.name),
                    Placeholder.unparsed("index", index.toString()),
                )
                then?.invoke()
            }
        }
    }

    private fun alert(key: String, vararg resolvers: net.kyori.adventure.text.minimessage.tag.resolver.TagResolver) {
        val messages = config().messages
        for (staff in server.onlinePlayers) {
            if (!staff.hasPermission(ALERTS)) continue
            threads.forPlayer(staff) { messages.send(staff, key, *resolvers) }
        }
    }

    private fun enabled(sender: CommandSender): Boolean {
        if (config().moderatorTools.enabled) return true
        config().messages.send(sender, "mod-disabled")
        return false
    }

    private fun notesEnabled(sender: CommandSender): Boolean {
        if (config().moderatorTools.notesEnabled) return true
        config().messages.send(sender, "notes-disabled")
        return false
    }

    companion object {
        const val ALERTS = "voxen.mod.alerts"
        const val DELETE = "voxen.mod.delete"
        const val INSPECT = "voxen.mod.inspect"
        const val DELETE_EXEMPT = "voxen.mod.delete.exempt"

        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
