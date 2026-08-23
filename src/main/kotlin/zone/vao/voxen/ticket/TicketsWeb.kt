package zone.vao.voxen.ticket

import zone.vao.voxen.Voxen
import zone.vao.voxen.config.WebConfig
import zone.vao.voxen.storage.TicketEntry
import zone.vao.voxen.util.ModeratorNames
import zone.vao.voxen.web.Html
import zone.vao.voxen.web.WebModule
import zone.vao.voxen.web.WebRequest
import java.time.Instant
import java.util.UUID

object TicketsWeb {

    const val VIEW = "tickets.view"
    const val MANAGE = "tickets.manage"

    private val FILTERS: Map<String, List<TicketEntry.Status>> = buildMap {
        put("queue", TicketEntry.Status.ACTIVE)
        for (status in TicketEntry.Status.entries) put(status.id, listOf(status))
        put("all", emptyList())
    }

    fun module(plugin: Voxen): WebModule = WebModule(
        id = "tickets",
        title = { plugin.configManager.config.web.label("tickets.title", "Tickets") },
        permission = VIEW,
        enabled = { plugin.configManager.config.helpop.let { it.tickets && it.web } },
        render = { request -> render(plugin, request) },
        submit = { request -> submit(plugin, request) },
    )

    private fun render(plugin: Voxen, request: WebRequest): String {
        val id = runCatching { UUID.fromString(request.param("id")) }.getOrNull()
        return if (id == null) list(plugin, request) else case(plugin, request, id)
    }

    private fun list(plugin: Voxen, request: WebRequest): String {
        val web = plugin.configManager.config.web
        val filter = request.param("filter")?.takeIf { it in FILTERS.keys } ?: "queue"
        val entries = plugin.ticketService.list(
            FILTERS.getValue(filter),
            plugin.configManager.config.helpop.queueLimit,
        )
        val tabs = FILTERS.keys.joinToString("") { key ->
            val active = if (key == filter) " class=\"active\"" else ""
            val label = web.label("tickets.filter.$key", key.replaceFirstChar(Char::titlecase))
            "<a href=\"/tickets?filter=${Html.encode(key)}\"$active>${Html.escape(label)}</a>"
        }
        val columns = listOf("updated", "player", "subject", "status", "moderator")
        val head = columns.joinToString("") { key ->
            "<th>${Html.escape(web.label("tickets.column.$key", key.replaceFirstChar(Char::titlecase)))}</th>"
        } + "<th>${Html.escape(web.label("tickets.column.open", "Open"))}</th>"
        val body = if (entries.isEmpty()) {
            "<div class=\"card\"><p class=\"empty\">${Html.escape(web.label("tickets.empty", "Nothing waiting here."))}</p></div>"
        } else {
            entries.joinToString(
                "",
                prefix = "<div class=\"card\"><table><thead><tr>$head</tr></thead><tbody>",
                postfix = "</tbody></table></div>",
            ) { entry -> row(web, filter, entry) }
        }
        return "<h1>${Html.escape(web.label("tickets.heading", "Player tickets"))}</h1>" +
            "<div class=\"tabs\">$tabs</div>$body"
    }

    private fun row(web: WebConfig, filter: String, entry: TicketEntry): String {
        val cells = listOf(
            "<span class=\"muted\">${Html.escape(time(entry.updatedAt))}</span>",
            Html.escape(entry.playerName),
            Html.escape(entry.subject),
            tag(web, entry.status),
            Html.escape(ModeratorNames.display(entry.handler) ?: "-"),
        ).joinToString("") { cell -> "<td>$cell</td>" }
        val open = web.label("tickets.action.open", "Open")
        val link = "/tickets?id=${Html.encode(entry.id.toString())}&filter=${Html.encode(filter)}"
        return "<tr>$cells<td><a href=\"$link\">${Html.escape(open)}</a></td></tr>"
    }

    private fun case(plugin: Voxen, request: WebRequest, id: UUID): String {
        val web = plugin.configManager.config.web
        val loaded = plugin.ticketService.case(id)
            ?: return "<div class=\"card\"><p class=\"empty\">${Html.escape(web.label("tickets.missing", "That ticket is gone."))}</p></div>"
        val entry = loaded.entry
        val fields = listOf(
            "player" to entry.playerName,
            "subject" to entry.subject,
            "status" to web.label("tickets.status.${entry.status.id}", entry.status.id),
            "server" to entry.server,
            "moderator" to (ModeratorNames.display(entry.handler) ?: "-"),
            "opened" to time(entry.createdAt),
        ).joinToString("", prefix = "<div class=\"card\"><table><tbody>", postfix = "</tbody></table></div>") { (key, value) ->
            "<tr><th>${Html.escape(web.label("tickets.field.$key", key.replaceFirstChar(Char::titlecase)))}</th>" +
                "<td>${Html.escape(value)}</td></tr>"
        }
        val conversation = loaded.messages.joinToString(
            "",
            prefix = "<h2>${Html.escape(web.label("tickets.conversation", "Conversation"))}</h2>" +
                "<div class=\"card\"><table><tbody>",
            postfix = "</tbody></table></div>",
        ) { line ->
            val who = if (line.staff) " class=\"marked\"" else ""
            "<tr$who><td class=\"muted\">${Html.escape(time(line.createdAt))}</td>" +
                "<td>${Html.escape(ModeratorNames.display(line.author).orEmpty())}</td>" +
                "<td>${Html.escape(line.content)}</td></tr>"
        }
        val back = "<div class=\"tabs\"><a href=\"/tickets?filter=${Html.encode(request.param("filter") ?: "queue")}\">" +
            "${Html.escape(web.label("tickets.back", "Back to the queue"))}</a></div>"
        val form = if (!request.allows(MANAGE) || !entry.status.active) "" else replyForm(plugin, request, web, entry)
        return "<h1>${Html.escape(web.label("tickets.case", "Ticket from"))} ${Html.escape(entry.playerName)}</h1>" +
            back + fields + conversation + form
    }

    private fun replyForm(plugin: Voxen, request: WebRequest, web: WebConfig, entry: TicketEntry): String {
        val target = request.link("id" to entry.id.toString(), "filter" to (request.param("filter") ?: "queue"))
        val placeholder = web.label("tickets.reply.placeholder", "Write back to the player")
        val send = web.label("tickets.reply.send", "Send")
        val close = web.label("tickets.action.close", "Close ticket")
        val limit = plugin.configManager.config.helpop.maxLength
        return "<h2>${Html.escape(web.label("tickets.reply.heading", "Reply"))}</h2>" +
            "<form class=\"actions\" method=\"post\" action=\"${Html.escape(target)}\">" +
            "<input type=\"hidden\" name=\"token\" value=\"${Html.escape(request.token)}\">" +
            "<input type=\"hidden\" name=\"id\" value=\"${Html.escape(entry.id.toString())}\">" +
            "<input class=\"reply\" type=\"text\" name=\"message\" maxlength=\"$limit\" autocomplete=\"off\" " +
            "placeholder=\"${Html.escape(placeholder)}\">" +
            "<button name=\"action\" value=\"reply\">${Html.escape(send)}</button>" +
            "<button name=\"action\" value=\"close\">${Html.escape(close)}</button>" +
            "</form>"
    }

    private fun tag(web: WebConfig, status: TicketEntry.Status): String =
        "<span class=\"tag ${status.id}\">${Html.escape(web.label("tickets.status.${status.id}", status.id))}</span>"

    private fun time(at: Long): String = TicketService.TIME_FORMAT.format(Instant.ofEpochMilli(at))

    private fun submit(plugin: Voxen, request: WebRequest) {
        if (!request.allows(MANAGE)) return
        val id = runCatching { UUID.fromString(request.param("id")) }.getOrNull() ?: return
        when (request.param("action")) {
            "reply" -> {
                val message = request.param("message") ?: return
                plugin.ticketService.answer(request.account, id, message)
            }

            "close" -> plugin.ticketService.close(request.account, id)
        }
    }
}
