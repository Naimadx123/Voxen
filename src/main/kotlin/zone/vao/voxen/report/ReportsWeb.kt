package zone.vao.voxen.report

import zone.vao.voxen.Voxen
import zone.vao.voxen.config.WebConfig
import zone.vao.voxen.storage.ReportAction
import zone.vao.voxen.storage.ReportEntry
import zone.vao.voxen.web.Html
import zone.vao.voxen.web.WebModule
import zone.vao.voxen.web.WebRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

object ReportsWeb {

    const val VIEW = "reports.view"
    const val MANAGE = "reports.manage"
    const val AUDIT = "reports.audit"

    private const val CUSTOM = "custom:"

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    private val FILTERS: Map<String, List<ReportEntry.Status>> = buildMap {
        put("queue", ReportEntry.Status.PENDING)
        for (status in ReportEntry.Status.entries) put(status.id, listOf(status))
        put("all", emptyList())
    }

    fun module(plugin: Voxen): WebModule = WebModule(
        id = "reports",
        title = { plugin.configManager.config.web.label("reports.title", "Reports") },
        permission = VIEW,
        enabled = { plugin.configManager.config.reports.let { it.enabled && it.web } },
        render = { request -> render(plugin, request) },
        submit = { request -> submit(plugin, request) },
    )

    fun auditModule(plugin: Voxen): WebModule = WebModule(
        id = "audit",
        title = { plugin.configManager.config.web.label("audit.title", "Audit log") },
        permission = AUDIT,
        enabled = { plugin.configManager.config.reports.let { it.enabled && it.web } },
        render = { request -> audit(plugin, request) },
    )

    private fun render(plugin: Voxen, request: WebRequest): String {
        val id = runCatching { UUID.fromString(request.param("id")) }.getOrNull()
        return if (id == null) list(plugin, request) else case(plugin, request, id)
    }

    private fun list(plugin: Voxen, request: WebRequest): String {
        val web = plugin.configManager.config.web
        val filter = request.param("filter")?.takeIf { it in FILTERS.keys } ?: "queue"
        val entries = plugin.reportService.list(FILTERS.getValue(filter), plugin.configManager.config.reports.queueLimit)
        val refreshing = request.param("refresh") == "on"
        val carried = if (refreshing) "&refresh=on" else ""
        val tabs = FILTERS.keys.joinToString("") { key ->
            val active = if (key == filter) " class=\"active\"" else ""
            val label = web.label("reports.filter.$key", key.replaceFirstChar(Char::titlecase))
            "<a href=\"/reports?filter=${Html.encode(key)}$carried\"$active>${Html.escape(label)}</a>"
        }
        val toggle = if (web.refreshSeconds <= 0) "" else {
            val label = "${web.label("reports.refresh", "Auto refresh")} (${web.refreshSeconds}s)"
            val active = if (refreshing) " active" else ""
            val href = "/reports?filter=${Html.encode(filter)}" + if (refreshing) "" else "&refresh=on"
            "<a class=\"switch$active\" href=\"$href\">${Html.escape(label)}</a>"
        }
        val columns = listOf("time", "player", "reporter", "reason", "channel", "status", "moderator")
        val head = columns.joinToString("") { key ->
            "<th>${Html.escape(web.label("reports.column.$key", key.replaceFirstChar(Char::titlecase)))}</th>"
        } + "<th>${Html.escape(web.label("reports.column.case", "Case"))}</th>"
        val body = if (entries.isEmpty()) {
            "<div class=\"card\"><p class=\"empty\">${Html.escape(web.label("reports.empty", "Nothing to handle here."))}</p></div>"
        } else {
            entries.joinToString(
                "",
                prefix = "<div class=\"card\"><table><thead><tr>$head</tr></thead><tbody>",
                postfix = "</tbody></table></div>",
            ) { entry -> row(web, filter, entry) }
        }
        return "<h1>${Html.escape(web.label("reports.heading", "Player reports"))}</h1>" +
            "<div class=\"tabs\">$tabs$toggle</div>$body"
    }

    private fun row(web: WebConfig, filter: String, entry: ReportEntry): String {
        val cells = listOf(
            "<span class=\"muted\">${Html.escape(TIME_FORMAT.format(Instant.ofEpochMilli(entry.createdAt)))}</span>",
            Html.escape(entry.targetName),
            Html.escape(entry.reporterName),
            Html.escape(entry.reason),
            "<span class=\"muted\">${Html.escape(entry.channel ?: "-")}</span>",
            tag(web, entry.status),
            Html.escape(entry.handler ?: "-"),
        ).joinToString("") { cell -> "<td>$cell</td>" }
        val open = web.label("reports.action.open", "Open")
        val link = "/reports?id=${Html.encode(entry.id.toString())}&filter=${Html.encode(filter)}"
        return "<tr>$cells<td><a href=\"$link\">${Html.escape(open)}</a></td></tr>"
    }

    private fun case(plugin: Voxen, request: WebRequest, id: UUID): String {
        val web = plugin.configManager.config.web
        val loaded = plugin.reportService.case(id)
            ?: return "<div class=\"card\"><p class=\"empty\">${Html.escape(web.label("reports.missing", "That report is gone."))}</p></div>"
        val entry = loaded.entry
        val fields = plugin.reportService.fields(entry)
            .filterNot { (key, _) -> key == "message" }
            .joinToString("", prefix = "<div class=\"card\"><table><tbody>", postfix = "</tbody></table></div>") { (key, value) ->
                val shown =
                    if (key == ReportService.STATUS_FIELD) web.label("reports.status.$value", value) else value
                "<tr><th>${Html.escape(web.label("reports.field.$key", key.replaceFirstChar(Char::titlecase)))}</th>" +
                    "<td>${Html.escape(shown)}</td></tr>"
            }
        val message = entry.messageContent?.let { content ->
            "<h2>${Html.escape(web.label("reports.message", "Reported message"))}</h2>" +
                "<div class=\"card\"><p class=\"quote\">${Html.escape(content)}</p></div>"
        }.orEmpty()
        val context = if (loaded.context.isEmpty()) "" else {
            loaded.context.joinToString(
                "",
                prefix = "<h2>${Html.escape(web.label("reports.context", "Context"))}</h2>" +
                    "<div class=\"card\"><table><tbody>",
                postfix = "</tbody></table></div>",
            ) { line ->
                val mark = if (line.id == entry.messageId) " class=\"marked\"" else ""
                "<tr$mark><td class=\"muted\">${Html.escape(TIME_FORMAT.format(Instant.ofEpochMilli(line.createdAt)))}</td>" +
                    "<td>${Html.escape(line.playerName)}</td><td>${Html.escape(line.content)}</td></tr>"
            }
        }
        val history = if (loaded.actions.isEmpty()) "" else {
            "<h2>${Html.escape(web.label("reports.history", "Audit trail"))}</h2>" + table(web, loaded.actions)
        }
        val back = "<div class=\"tabs\"><a href=\"/reports?filter=${Html.encode(request.param("filter") ?: "queue")}\">" +
            "${Html.escape(web.label("reports.back", "Back to the queue"))}</a></div>"
        val buttons = if (!request.allows(MANAGE)) "" else actions(plugin, request, web, entry)
        return "<h1>${Html.escape(web.label("reports.case", "Report on"))} ${Html.escape(entry.targetName)}</h1>" +
            back + fields + buttons + message + context + history
    }

    private fun audit(plugin: Voxen, request: WebRequest): String {
        val web = plugin.configManager.config.web
        val entries = plugin.reportService.actions(null, plugin.configManager.config.reports.auditLimit)
        val heading = "<h1>${Html.escape(web.label("audit.heading", "Moderator actions"))}</h1>"
        if (entries.isEmpty()) {
            return heading + "<div class=\"card\"><p class=\"empty\">" +
                "${Html.escape(web.label("audit.empty", "Nothing has been done yet."))}</p></div>"
        }
        return heading + table(web, entries, links = request.allows(VIEW))
    }

    private fun table(web: WebConfig, entries: List<ReportAction>, links: Boolean = false): String {
        val columns = listOf("time", "moderator", "action", "detail")
        val head = columns.joinToString("") { key ->
            "<th>${Html.escape(web.label("audit.column.$key", key.replaceFirstChar(Char::titlecase)))}</th>"
        } + if (links) "<th>${Html.escape(web.label("audit.column.report", "Report"))}</th>" else ""
        return entries.joinToString(
            "",
            prefix = "<div class=\"card\"><table><thead><tr>$head</tr></thead><tbody>",
            postfix = "</tbody></table></div>",
        ) { entry ->
            val link = if (!links) "" else {
                "<td><a href=\"/reports?id=${Html.encode(entry.report.toString())}\">" +
                    "${Html.escape(web.label("reports.action.open", "Open"))}</a></td>"
            }
            "<tr><td class=\"muted\">${Html.escape(TIME_FORMAT.format(Instant.ofEpochMilli(entry.createdAt)))}</td>" +
                "<td>${Html.escape(entry.actor)}</td>" +
                "<td>${Html.escape(web.label("audit.action.${entry.action}", entry.action))}</td>" +
                "<td>${Html.escape(entry.detail ?: "-")}</td>$link</tr>"
        }
    }

    private fun tag(web: WebConfig, status: ReportEntry.Status): String =
        "<span class=\"tag ${status.id}\">${Html.escape(web.label("reports.status.${status.id}", status.id))}</span>"

    private fun actions(plugin: Voxen, request: WebRequest, web: WebConfig, entry: ReportEntry): String {
        val buttons = buildString {
            for (action in ReportService.Action.entries) {
                if (action.status == entry.status) continue
                val label = web.label("reports.action.${action.id}", action.id.replaceFirstChar(Char::titlecase))
                append("<button name=\"action\" value=\"${action.id}\">${Html.escape(label)}</button>")
            }
            plugin.configManager.config.reports.actions.forEachIndexed { index, custom ->
                val label = plugin.contentRenderer.plain(custom.label)
                append("<button name=\"action\" value=\"$CUSTOM$index\">${Html.escape(label)}</button>")
            }
        }
        val target = request.link("id" to entry.id.toString(), "filter" to (request.param("filter") ?: "queue"))
        return "<form class=\"actions\" method=\"post\" action=\"${Html.escape(target)}\">" +
            "<input type=\"hidden\" name=\"token\" value=\"${Html.escape(request.token)}\">" +
            "<input type=\"hidden\" name=\"id\" value=\"${Html.escape(entry.id.toString())}\">" +
            buttons +
            "</form>"
    }

    private fun submit(plugin: Voxen, request: WebRequest) {
        if (!request.allows(MANAGE)) return
        val id = runCatching { UUID.fromString(request.param("id")) }.getOrNull() ?: return
        val raw = request.param("action") ?: return
        if (!raw.startsWith(CUSTOM)) {
            val action = ReportService.Action.from(raw) ?: return
            plugin.reportService.apply(request.user.name, id, action)
            return
        }
        val custom = plugin.configManager.config.reports.actions.getOrNull(raw.removePrefix(CUSTOM).toIntOrNull() ?: -1)
            ?: return
        val entry = plugin.reportService.find(id) ?: return
        plugin.reportService.run(plugin.server.consoleSender, entry, custom)
    }
}
