package zone.vao.voxen.report

import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import zone.vao.voxen.config.ReportsConfig
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.moderation.ModeratorService
import zone.vao.voxen.storage.ChatLogEntry
import zone.vao.voxen.storage.PlayerDataService
import zone.vao.voxen.storage.PlayerStorage
import zone.vao.voxen.storage.ReportAction
import zone.vao.voxen.storage.ReportEntry
import zone.vao.voxen.util.Durations
import zone.vao.voxen.util.Pages
import zone.vao.voxen.util.Threads
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ReportService(
    private val server: Server,
    private val config: () -> VoxenConfig,
    private val playerData: PlayerDataService,
    private val moderators: ModeratorService,
    private val threads: Threads,
) {

    private val lastUse = ConcurrentHashMap<UUID, Long>()
    private val index = MessageIndex { config().reports.messageKeep }

    var submitDialog: ((Player, ModeratorService.Target, MessageIndex.Entry?) -> Unit)? = null
    var queueDialog: ((Player, List<ReportEntry>) -> Unit)? = null
    var viewDialog: ((Player, Case) -> Unit)? = null
    var historyDialog: ((Player, Case) -> Unit)? = null

    var muteAction: ((CommandSender, String, String, String?) -> Unit)? = null

    data class Case(
        val entry: ReportEntry,
        val context: List<ChatLogEntry>,
        val actions: List<ReportAction>,
    )

    enum class Action(val status: ReportEntry.Status?) {
        CLAIM(ReportEntry.Status.CLAIMED),
        RESOLVE(ReportEntry.Status.RESOLVED),
        DISMISS(ReportEntry.Status.DISMISSED),
        DELETE(null);

        val id: String
            get() = name.lowercase()

        companion object {
            fun from(value: String?): Action? = entries.firstOrNull { it.id == value?.trim()?.lowercase() }
        }
    }

    fun remember(id: UUID, author: Player, channel: String, content: String) {
        if (!config().reports.enabled) return
        index.remember(
            MessageIndex.Entry(
                id = id,
                author = author.uniqueId,
                authorName = author.name,
                channel = channel,
                content = content,
                server = config().network.serverId,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    fun attach(id: UUID, signed: SignedMessage) {
        index.attach(id, signed)
    }

    fun clear() {
        index.clear()
    }

    fun chatButton(author: Player, messageId: UUID, viewer: Player): Component? {
        val settings = config().reports
        if (!settings.enabled || !settings.chatButton) return null
        if (author.uniqueId == viewer.uniqueId) return null
        if (!viewer.hasPermission(PERMISSION)) return null
        if (settings.chatButtonStaffOnly && !viewer.hasPermission(MANAGE)) return null
        if (author.hasPermission(EXEMPT)) return null
        val entry = index.get(messageId) ?: return null
        val command = "/${config().commands.report.firstOrNull() ?: "report"} ${author.name} #${entry.token}"
        val click = if (settings.dialogs) ClickEvent.runCommand(command) else ClickEvent.suggestCommand("$command ")
        return config().messages
            .line(viewer, "report-button", Placeholder.unparsed("player", author.name))
            .clickEvent(click)
    }

    fun submit(reporter: Player, target: ModeratorService.Target, reason: String?) {
        val text = reason?.trim()?.ifEmpty { null }
        val token = text?.substringBefore(' ')?.takeIf { it.startsWith("#") }
        val reference = token?.let { index.byToken(it) } ?: index.latest(target.uuid)
        val rest = if (token == null) text else text.removePrefix(token).trim().ifEmpty { null }
        submit(reporter, target, rest, reference)
    }

    fun submit(
        reporter: Player,
        target: ModeratorService.Target,
        reason: String?,
        reference: MessageIndex.Entry?,
    ) {
        val settings = config().reports
        val messages = config().messages
        if (!enabled(reporter)) return
        if (!settings.allowSelf && reporter.uniqueId == target.uuid) {
            messages.send(reporter, "report-self")
            return
        }
        val online = server.getPlayer(target.uuid)
        if (online?.hasPermission(EXEMPT) == true) {
            messages.send(reporter, "report-exempt", Placeholder.unparsed("player", target.name))
            return
        }
        if (!settings.allowOffline && online == null) {
            messages.send(reporter, "report-target-offline", Placeholder.unparsed("player", target.name))
            return
        }
        if (reason == null) {
            if (settings.dialogs) submitDialog?.invoke(reporter, target, reference)
            else messages.send(reporter, "report-needs-reason")
            return
        }
        val text = reasonText(reporter, reason, settings) ?: return
        val now = System.currentTimeMillis()
        val last = lastUse[reporter.uniqueId]
        if (last != null && now - last < settings.cooldownMillis && !reporter.hasPermission(BYPASS_COOLDOWN)) {
            messages.send(
                reporter,
                "report-cooldown",
                Placeholder.unparsed("remaining", Durations.humanize(last + settings.cooldownMillis - now)),
            )
            return
        }
        val entry = ReportEntry(
            id = UUID.randomUUID(),
            target = target.uuid,
            targetName = target.name,
            reporter = reporter.uniqueId,
            reporterName = reporter.name,
            reason = text,
            server = config().network.serverId,
            channel = reference?.channel,
            messageId = reference?.id,
            messageContent = reference?.content,
            messageAt = reference?.createdAt,
            status = ReportEntry.Status.OPEN,
            handler = null,
            createdAt = now,
            updatedAt = now,
        )
        playerData.durable({ storage ->
            if (!settings.allowDuplicates) {
                val blocked = when {
                    reference != null -> storage.hasReportFor(reference.id) to "report-message-duplicate"
                    else -> storage.hasOpenReport(reporter.uniqueId, target.uuid) to "report-duplicate"
                }
                if (blocked.first) {
                    threads.forPlayer(reporter) {
                        messages.send(reporter, blocked.second, Placeholder.unparsed("player", target.name))
                    }
                    return@durable
                }
            }
            storage.saveReport(entry)
            storage.saveReportAction(action(entry.id, reporter.name, OPENED, text, now))
            lastUse[reporter.uniqueId] = now
            val pending = storage.reportCount(ReportEntry.Status.PENDING)
            threads.main {
                messages.send(reporter, "report-sent", Placeholder.unparsed("player", target.name))
                if (settings.notifyStaff) {
                    alert(
                        "report-alert",
                        Placeholder.unparsed("player", target.name),
                        Placeholder.unparsed("reporter", reporter.name),
                        Placeholder.unparsed("reason", text),
                        Placeholder.unparsed("amount", pending.toString()),
                        Placeholder.unparsed("message", entry.messageContent ?: "-"),
                    )
                }
            }
        }, { threads.main { messages.send(reporter, "storage-busy") } })
    }

    fun queue(sender: CommandSender, page: Int = 1) {
        val messages = config().messages
        if (!enabled(sender)) return
        playerData.async { storage ->
            val entries = pending(storage)
            threads.main {
                if (entries.isEmpty()) {
                    messages.send(sender, "report-list-empty")
                    return@main
                }
                messages.send(sender, "report-list-header", Placeholder.unparsed("amount", entries.size.toString()))
                val view = Pages.of(entries, page)
                view.items.forEachIndexed { offset, entry ->
                    sender.sendMessage(
                        messages.line(sender, "report-list-entry", *describe(sender, view.offset + offset + 1, entry))
                    )
                }
                if (view.hasNext) {
                    sender.sendMessage(
                        messages.line(
                            sender,
                            "page-footer",
                            Placeholder.unparsed("page", view.number.toString()),
                            Placeholder.unparsed("pages", view.count.toString()),
                            Placeholder.unparsed("command", "/voxen reports ${view.number + 1}"),
                        )
                    )
                }
            }
        }
    }

    fun openQueue(viewer: Player) {
        if (!enabled(viewer)) return
        playerData.async { storage ->
            val entries = pending(storage)
            threads.forPlayer(viewer) { queueDialog?.invoke(viewer, entries) }
        }
    }

    fun show(sender: CommandSender, number: Int) {
        if (!enabled(sender)) return
        playerData.async { storage ->
            val entry = pending(storage).getOrNull(number - 1)
            if (entry == null) {
                threads.main { config().messages.send(sender, "report-invalid-index") }
                return@async
            }
            deliver(sender, case(storage, entry))
        }
    }

    fun openReport(viewer: Player, id: UUID) {
        if (!enabled(viewer)) return
        playerData.async { storage ->
            val entry = storage.report(id)
            if (entry == null) {
                threads.forPlayer(viewer) { config().messages.send(viewer, "report-invalid-index") }
                return@async
            }
            deliver(viewer, case(storage, entry))
        }
    }

    fun openHistory(viewer: Player, id: UUID) {
        if (!enabled(viewer)) return
        playerData.async { storage ->
            val entry = storage.report(id) ?: return@async
            val loaded = case(storage, entry)
            threads.forPlayer(viewer) { historyDialog?.invoke(viewer, loaded) }
        }
    }

    fun act(sender: CommandSender, number: Int, choice: Action) {
        if (!enabled(sender)) return
        playerData.async { storage ->
            val entry = pending(storage).getOrNull(number - 1)
            if (entry == null) {
                threads.main { config().messages.send(sender, "report-invalid-index") }
                return@async
            }
            finish(storage, sender, entry.id, choice)
        }
    }

    fun act(sender: CommandSender, id: UUID, choice: Action, then: (() -> Unit)? = null) {
        if (!enabled(sender)) return
        playerData.async { storage ->
            finish(storage, sender, id, choice)
            if (then != null) threads.main { then() }
        }
    }

    fun warn(sender: CommandSender, entry: ReportEntry, then: (() -> Unit)? = null) {
        moderators.warn(sender, target(entry), entry.reason)
        audit(entry.id, sender.name, WARNED, entry.reason)
        then?.invoke()
    }

    fun mute(sender: CommandSender, entry: ReportEntry, duration: String, then: (() -> Unit)? = null) {
        muteAction?.invoke(sender, entry.targetName, duration, entry.reason)
        audit(entry.id, sender.name, MUTED, duration)
        then?.invoke()
    }

    fun deleteMessage(sender: CommandSender, entry: ReportEntry, then: (() -> Unit)? = null) {
        val signed = entry.messageId?.let { index.get(it) }?.signed
        if (signed == null) {
            config().messages.send(sender, "report-message-gone")
            then?.invoke()
            return
        }
        if (moderators.deleteSigned(sender, target(entry), signed)) {
            audit(entry.id, sender.name, MESSAGE_DELETED, entry.messageContent)
        }
        then?.invoke()
    }

    fun run(sender: CommandSender, entry: ReportEntry, custom: ReportsConfig.Action) {
        val command = custom.command
            .replace("<player>", argument(entry.targetName))
            .replace("<uuid>", entry.target.toString())
            .replace("<reporter>", argument(entry.reporterName))
            .replace("<reason>", argument(entry.reason))
            .replace("<message>", argument(entry.messageContent.orEmpty()))
            .replace("<moderator>", argument(sender.name))
        threads.main {
            val executor = if (custom.console) server.consoleSender else sender
            runCatching { server.dispatchCommand(executor, command) }
                .onFailure { server.logger.warning("Report action command '$command' failed: ${it.message}") }
        }
        audit(entry.id, sender.name, COMMAND, command)
        if (custom.resolve) act(sender, entry.id, Action.RESOLVE)
    }

    fun list(statuses: Collection<ReportEntry.Status>, limit: Int): List<ReportEntry> =
        playerData.blocking { storage -> storage.reports(statuses, limit) } ?: emptyList()

    fun actions(report: UUID?, limit: Int): List<ReportAction> =
        playerData.blocking { storage -> storage.reportActions(report, limit) } ?: emptyList()

    fun case(id: UUID): Case? =
        playerData.blocking { storage -> storage.report(id)?.let { entry -> case(storage, entry) } }

    fun apply(actor: String, id: UUID, choice: Action): Boolean {
        val updated = playerData.blocking { storage -> handle(storage, actor, id, choice) } ?: return false
        announce(server.consoleSender, updated, choice)
        return true
    }

    fun find(id: UUID): ReportEntry? = playerData.blocking { storage -> storage.report(id) }

    internal fun fields(entry: ReportEntry): List<Pair<String, String>> = buildList {
        add("player" to entry.targetName)
        add("reporter" to entry.reporterName)
        add("reason" to entry.reason)
        add(STATUS_FIELD to entry.status.id)
        add("channel" to (entry.channel ?: "-"))
        add("server" to entry.server)
        add("moderator" to (entry.handler ?: "-"))
        add("time" to TIME_FORMAT.format(Instant.ofEpochMilli(entry.createdAt)))
        entry.messageContent?.let { add("message" to it) }
        entry.messageId?.let { add("message-id" to it.toString()) }
    }

    internal fun fieldLines(sender: CommandSender, entry: ReportEntry): List<Component> {
        val messages = config().messages
        return fields(entry).map { (key, value) ->
            val shown = if (key == STATUS_FIELD) messages.raw(sender, "report-status-$value") else value
            messages.line(
                sender,
                "report-view-line",
                Placeholder.unparsed("key", messages.raw(sender, "report-field-$key")),
                Placeholder.unparsed("value", shown),
            )
        }
    }

    internal fun contextLines(sender: CommandSender, loaded: Case): List<Component> {
        if (loaded.context.isEmpty()) return emptyList()
        val messages = config().messages
        return buildList {
            add(messages.line(sender, "report-context-header"))
            for (line in loaded.context) {
                add(
                    messages.line(
                        sender,
                        if (line.id == loaded.entry.messageId) "report-context-reported" else "report-context-entry",
                        Placeholder.unparsed("time", TIME_FORMAT.format(Instant.ofEpochMilli(line.createdAt))),
                        Placeholder.unparsed("player", line.playerName),
                        Placeholder.unparsed("message", line.content),
                    )
                )
            }
        }
    }

    internal fun historyLines(sender: CommandSender, loaded: Case): List<Component> {
        if (loaded.actions.isEmpty()) return emptyList()
        val messages = config().messages
        return buildList {
            add(messages.line(sender, "report-history-header"))
            for (entry in loaded.actions) {
                add(
                    messages.line(
                        sender,
                        "report-history-entry",
                        Placeholder.unparsed("time", TIME_FORMAT.format(Instant.ofEpochMilli(entry.createdAt))),
                        Placeholder.unparsed("moderator", entry.actor),
                        Placeholder.unparsed("action", messages.raw(sender, "report-action-${entry.action}")),
                        Placeholder.unparsed("detail", entry.detail ?: "-"),
                    )
                )
            }
        }
    }

    internal fun target(entry: ReportEntry): ModeratorService.Target =
        ModeratorService.Target(entry.target, entry.targetName)

    internal fun describe(sender: CommandSender, number: Int, entry: ReportEntry): Array<TagResolver> = arrayOf(
        Placeholder.unparsed("index", number.toString()),
        Placeholder.unparsed("time", TIME_FORMAT.format(Instant.ofEpochMilli(entry.createdAt))),
        Placeholder.unparsed("player", entry.targetName),
        Placeholder.unparsed("reporter", entry.reporterName),
        Placeholder.unparsed("reason", entry.reason),
        Placeholder.unparsed("status", config().messages.raw(sender, "report-status-${entry.status.id}")),
        Placeholder.unparsed("channel", entry.channel ?: "-"),
        Placeholder.unparsed("server", entry.server),
        Placeholder.unparsed("moderator", entry.handler ?: "-"),
        Placeholder.unparsed("message", entry.messageContent ?: "-"),
    )

    private fun deliver(sender: CommandSender, loaded: Case) {
        val viewer = sender as? Player
        if (viewer != null && config().reports.dialogs && viewDialog != null) {
            threads.forPlayer(viewer) { viewDialog?.invoke(viewer, loaded) }
            return
        }
        val messages = config().messages
        threads.main {
            messages.send(sender, "report-view-header", Placeholder.unparsed("player", loaded.entry.targetName))
            for (line in fieldLines(sender, loaded.entry)) sender.sendMessage(line)
            for (line in contextLines(sender, loaded)) sender.sendMessage(line)
            for (line in historyLines(sender, loaded)) sender.sendMessage(line)
        }
    }

    private fun finish(storage: PlayerStorage, sender: CommandSender, id: UUID, choice: Action) {
        val current = storage.report(id)
        if (current != null && current.status == choice.status) {
            threads.main { unchanged(sender, current) }
            return
        }
        val updated = handle(storage, sender.name, id, choice)
        if (updated == null) threads.main { config().messages.send(sender, "report-invalid-index") }
        else announce(sender, updated, choice)
    }

    private fun unchanged(sender: CommandSender, entry: ReportEntry) {
        val messages = config().messages
        messages.send(
            sender,
            "report-unchanged",
            Placeholder.unparsed("player", entry.targetName),
            Placeholder.unparsed("status", messages.raw(sender, "report-status-${entry.status.id}")),
            Placeholder.unparsed("moderator", entry.handler ?: "-"),
        )
    }

    private fun handle(storage: PlayerStorage, actor: String, id: UUID, choice: Action): ReportEntry? {
        val entry = storage.report(id) ?: return null
        val now = System.currentTimeMillis()
        val status = choice.status ?: return if (storage.deleteReport(id)) entry else null
        if (entry.status == status) return null
        if (!storage.updateReport(id, status, actor, now)) return null
        storage.saveReportAction(action(id, actor, choice.id, null, now))
        return entry.copy(status = status, handler = actor, updatedAt = now)
    }

    private fun case(storage: PlayerStorage, entry: ReportEntry): Case {
        val settings = config().reports
        val context =
            if (settings.context <= 0 || entry.channel == null || entry.messageAt == null) emptyList()
            else storage.chatContext(entry.channel, entry.messageAt, settings.context + 1, settings.context)
        return Case(entry, context, storage.reportActions(entry.id, settings.auditLimit))
    }

    private fun announce(sender: CommandSender, entry: ReportEntry, choice: Action) {
        val messages = config().messages
        threads.main {
            messages.send(
                sender,
                when (choice) {
                    Action.CLAIM -> "report-claimed"
                    Action.RESOLVE -> "report-resolved"
                    Action.DISMISS -> "report-dismissed"
                    Action.DELETE -> "report-deleted"
                },
                Placeholder.unparsed("player", entry.targetName),
                Placeholder.unparsed("reporter", entry.reporterName),
                Placeholder.unparsed("moderator", entry.handler ?: sender.name),
            )
            if (!config().reports.notifyReporter || choice == Action.CLAIM) return@main
            val online = server.getPlayer(entry.reporter) ?: return@main
            threads.forPlayer(online) {
                messages.send(
                    online,
                    "report-status-changed",
                    Placeholder.unparsed("player", entry.targetName),
                    Placeholder.unparsed("status", messages.raw(online, "report-status-${entry.status.id}")),
                    Placeholder.unparsed("moderator", entry.handler ?: sender.name),
                )
            }
        }
    }

    private fun audit(report: UUID, actor: String, kind: String, detail: String?) {
        val entry = action(report, actor, kind, detail, System.currentTimeMillis())
        playerData.durable("The report action '$kind'") { storage -> storage.saveReportAction(entry) }
    }

    private fun action(report: UUID, actor: String, kind: String, detail: String?, at: Long) = ReportAction(
        id = UUID.randomUUID(),
        report = report,
        actor = actor,
        action = kind,
        detail = detail?.let(::argument),
        createdAt = at,
    )

    private fun pending(storage: PlayerStorage): List<ReportEntry> =
        storage.reports(ReportEntry.Status.PENDING, config().reports.queueLimit)

    private fun reasonText(reporter: Player, raw: String, settings: ReportsConfig): String? {
        val messages = config().messages
        val preset = settings.reason(raw)
        if (preset != null) {
            if (preset.permission != null && !reporter.hasPermission(preset.permission)) {
                messages.send(reporter, "no-permission")
                return null
            }
            return preset.label
        }
        if (!settings.freeText) {
            messages.send(
                reporter,
                "report-reason-unknown",
                Placeholder.unparsed("reasons", settings.reasons.joinToString(", ") { it.id }),
            )
            return null
        }
        if (raw.length < settings.minLength) {
            messages.send(reporter, "report-too-short", Placeholder.unparsed("min", settings.minLength.toString()))
            return null
        }
        return raw.take(settings.maxLength)
    }

    private fun argument(value: String): String =
        value.filter { it.code >= 32 }.replace(WHITESPACE, " ").trim().take(200)

    private fun alert(key: String, vararg resolvers: TagResolver) {
        val messages = config().messages
        for (staff in server.onlinePlayers) {
            if (!staff.hasPermission(ModeratorService.ALERTS)) continue
            threads.forPlayer(staff) { messages.send(staff, key, *resolvers) }
        }
        messages.send(server.consoleSender, key, *resolvers)
    }

    private fun enabled(sender: CommandSender): Boolean {
        if (config().reports.enabled) return true
        config().messages.send(sender, "report-disabled")
        return false
    }

    companion object {
        const val PERMISSION = "voxen.report"
        const val EXEMPT = "voxen.report.exempt"
        const val MANAGE = "voxen.report.manage"
        const val BYPASS_COOLDOWN = "voxen.bypass.cooldown"

        const val STATUS_FIELD = "status"

        const val OPENED = "opened"
        const val WARNED = "warned"
        const val MUTED = "muted"
        const val MESSAGE_DELETED = "message-deleted"
        const val COMMAND = "command"

        private val WHITESPACE = Regex("\\s+")

        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
