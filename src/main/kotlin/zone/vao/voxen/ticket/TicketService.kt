package zone.vao.voxen.ticket

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import zone.vao.voxen.TicketInfo
import zone.vao.voxen.config.HelpopConfig
import zone.vao.voxen.event.TicketUpdateEvent
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.storage.PlayerDataService
import zone.vao.voxen.storage.PlayerStorage
import zone.vao.voxen.storage.TicketEntry
import zone.vao.voxen.storage.TicketMessage
import zone.vao.voxen.util.Pages
import zone.vao.voxen.util.ModeratorNames
import zone.vao.voxen.util.Threads
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class TicketService(
    private val server: Server,
    private val config: () -> VoxenConfig,
    private val playerData: PlayerDataService,
    private val threads: Threads,
) {

    @Volatile
    var panelDialog: ((Player, List<TicketEntry>) -> Unit)? = null

    @Volatile
    var viewDialog: ((Player, Case) -> Unit)? = null

    data class Case(val entry: TicketEntry, val messages: List<TicketMessage>)

    private fun settings(): HelpopConfig = config().helpop

    fun open(player: Player, subject: String) {
        val messages = config().messages
        val settings = settings()
        val text = subject.trim().take(settings.maxLength)
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        val entry = TicketEntry(
            id = UUID.randomUUID(),
            player = player.uniqueId,
            playerName = player.name,
            subject = text,
            server = config().network.serverId,
            status = TicketEntry.Status.OPEN,
            handler = null,
            createdAt = now,
            updatedAt = now,
        )
        playerData.durable({ storage ->
            if (settings.maxOpen > 0) {
                val open = storage.ticketCount(player.uniqueId, TicketEntry.Status.ACTIVE)
                if (open >= settings.maxOpen) {
                    threads.forPlayer(player) {
                        messages.send(
                            player,
                            "ticket-limit",
                            Placeholder.unparsed("amount", settings.maxOpen.toString()),
                        )
                    }
                    return@durable
                }
            }
            storage.saveTicket(entry)
            storage.saveTicketMessage(message(entry.id, player.name, staff = false, content = text, at = now))
            announce(entry, TicketUpdateEvent.Action.OPENED, player.name, staff = false, message = text)
            val waiting = storage.tickets(TicketEntry.Status.ACTIVE, settings.queueLimit).size
            threads.main {
                messages.send(player, "ticket-opened", Placeholder.unparsed("subject", text))
                if (settings.notifyStaff) {
                    alert(
                        "ticket-alert",
                        Placeholder.unparsed("player", player.name),
                        Placeholder.unparsed("subject", text),
                        Placeholder.unparsed("amount", waiting.toString()),
                    )
                }
            }
        }, { threads.main { messages.send(player, "storage-busy") } })
    }

    fun reply(player: Player, id: UUID, content: String) {
        val messages = config().messages
        val settings = settings()
        val text = content.trim().take(settings.maxLength)
        if (text.isEmpty()) {
            messages.send(player, "ticket-needs-message")
            return
        }
        playerData.durable({ storage ->
            val entry = storage.ticket(id)
            if (entry == null || entry.player != player.uniqueId) {
                threads.forPlayer(player) { messages.send(player, "ticket-not-found") }
                return@durable
            }
            if (!entry.status.active) {
                threads.forPlayer(player) { messages.send(player, "ticket-already-closed") }
                return@durable
            }
            val now = System.currentTimeMillis()
            storage.saveTicketMessage(message(entry.id, player.name, staff = false, content = text, at = now))
            storage.updateTicket(entry.id, TicketEntry.Status.OPEN, null, now)
            announce(
                entry.copy(status = TicketEntry.Status.OPEN, updatedAt = now),
                TicketUpdateEvent.Action.REPLIED,
                player.name,
                staff = false,
                message = text,
            )
            threads.main {
                messages.send(player, "ticket-reply-sent")
                if (settings.notifyStaff) {
                    alert(
                        "ticket-reply-alert",
                        Placeholder.unparsed("player", player.name),
                        Placeholder.unparsed("subject", entry.subject),
                        Placeholder.unparsed("message", text),
                    )
                }
            }
        }, { threads.main { messages.send(player, "storage-busy") } })
    }

    fun answer(author: String, id: UUID, content: String): Boolean {
        val text = content.trim().take(settings().maxLength)
        if (text.isEmpty()) return false
        val entry = playerData.blocking { storage ->
            val found = storage.ticket(id)?.takeIf { it.status.active } ?: return@blocking null
            val now = System.currentTimeMillis()
            storage.saveTicketMessage(message(found.id, author, staff = true, content = text, at = now))
            storage.updateTicket(found.id, TicketEntry.Status.ANSWERED, author, now)
            found.copy(status = TicketEntry.Status.ANSWERED, handler = author, updatedAt = now)
        } ?: return false
        announce(entry, TicketUpdateEvent.Action.REPLIED, author, staff = true, message = text)
        notify(entry, "ticket-staff-reply", Placeholder.unparsed("message", text))
        return true
    }

    fun close(author: String, id: UUID): Boolean {
        val entry = playerData.blocking { storage ->
            val found = storage.ticket(id)?.takeIf { it.status.active } ?: return@blocking null
            val now = System.currentTimeMillis()
            storage.updateTicket(found.id, TicketEntry.Status.CLOSED, author, now)
            found.copy(status = TicketEntry.Status.CLOSED, handler = author, updatedAt = now)
        } ?: return false
        announce(entry, TicketUpdateEvent.Action.CLOSED, author, staff = true, message = null)
        notify(entry, "ticket-was-closed")
        return true
    }

    fun openPanel(player: Player) {
        val messages = config().messages
        val settings = settings()
        if (!settings.tickets) {
            messages.send(player, "ticket-disabled")
            return
        }
        playerData.async { storage ->
            val entries = storage.ticketsOf(player.uniqueId, emptyList(), settings.queueLimit)
            threads.forPlayer(player) {
                if (settings.dialogs && panelDialog != null) {
                    panelDialog?.invoke(player, entries)
                    return@forPlayer
                }
                if (entries.isEmpty()) {
                    messages.send(player, "ticket-list-empty")
                    return@forPlayer
                }
                messages.send(player, "ticket-list-header", Placeholder.unparsed("amount", entries.size.toString()))
                entries.forEachIndexed { index, entry ->
                    messages.send(player, "ticket-list-entry", *describe(player, index + 1, entry))
                }
            }
        }
    }

    fun openTicket(player: Player, id: UUID) {
        val messages = config().messages
        playerData.async { storage ->
            val loaded = load(storage, id)?.takeIf { it.entry.player == player.uniqueId }
            threads.forPlayer(player) {
                if (loaded == null) {
                    messages.send(player, "ticket-not-found")
                    return@forPlayer
                }
                if (settings().dialogs && viewDialog != null) {
                    viewDialog?.invoke(player, loaded)
                    return@forPlayer
                }
                for (line in conversation(player, loaded)) player.sendMessage(line)
            }
        }
    }

    fun show(sender: CommandSender, id: UUID) {
        val messages = config().messages
        playerData.async { storage ->
            val loaded = load(storage, id)
            threads.main {
                if (loaded == null) {
                    messages.send(sender, "ticket-not-found")
                    return@main
                }
                for (line in conversation(sender, loaded)) sender.sendMessage(line)
            }
        }
    }

    fun queue(sender: CommandSender, page: Int) {
        val messages = config().messages
        val settings = settings()
        playerData.async { storage ->
            val entries = storage.tickets(TicketEntry.Status.ACTIVE, settings.queueLimit)
            threads.main {
                if (entries.isEmpty()) {
                    messages.send(sender, "ticket-list-empty")
                    return@main
                }
                val paged = Pages.of(entries, page, PAGE_SIZE)
                messages.send(sender, "ticket-list-header", Placeholder.unparsed("amount", entries.size.toString()))
                paged.items.forEachIndexed { index, entry ->
                    messages.send(sender, "ticket-list-entry", *describe(sender, paged.offset + index + 1, entry))
                }
            }
        }
    }

    fun pending(sender: CommandSender, number: Int, then: (TicketEntry) -> Unit) {
        val messages = config().messages
        playerData.async { storage ->
            val entry = storage.tickets(TicketEntry.Status.ACTIVE, settings().queueLimit).getOrNull(number - 1)
            threads.main {
                if (entry == null) messages.send(sender, "ticket-invalid-index") else then(entry)
            }
        }
    }

    fun list(statuses: Collection<TicketEntry.Status>, limit: Int): List<TicketEntry> =
        playerData.blocking { storage -> storage.tickets(statuses, limit) } ?: emptyList()

    fun case(id: UUID): Case? = playerData.blocking { storage -> load(storage, id) }

    fun purge() {
        val days = settings().keepDays
        if (days <= 0) return
        playerData.async { it.purgeTickets(System.currentTimeMillis() - days * 86_400_000L) }
    }

    internal fun describe(sender: CommandSender, number: Int, entry: TicketEntry): Array<TagResolver> {
        val messages = config().messages
        return arrayOf(
            Placeholder.unparsed("index", number.toString()),
            Placeholder.unparsed("player", entry.playerName),
            Placeholder.unparsed("subject", entry.subject),
            Placeholder.unparsed("status", messages.raw(sender, "ticket-status-${entry.status.id}")),
            Placeholder.unparsed("moderator", ModeratorNames.display(entry.handler) ?: "-"),
            Placeholder.unparsed("time", TIME_FORMAT.format(Instant.ofEpochMilli(entry.updatedAt))),
        )
    }

    internal fun conversation(sender: CommandSender, case: Case): List<Component> {
        val messages = config().messages
        val header = messages.line(
            sender,
            "ticket-view-header",
            Placeholder.unparsed("player", case.entry.playerName),
            Placeholder.unparsed("subject", case.entry.subject),
            Placeholder.unparsed("status", messages.raw(sender, "ticket-status-${case.entry.status.id}")),
        )
        return listOf(header) + case.messages.map { line ->
            messages.line(
                sender,
                if (line.staff) "ticket-message-staff" else "ticket-message-player",
                Placeholder.unparsed("author", ModeratorNames.display(line.author).orEmpty()),
                Placeholder.unparsed("message", line.content),
                Placeholder.unparsed("time", TIME_FORMAT.format(Instant.ofEpochMilli(line.createdAt))),
            )
        }
    }

    private fun load(storage: PlayerStorage, id: UUID): Case? {
        val entry = storage.ticket(id) ?: return null
        return Case(entry, storage.ticketMessages(id, settings().historyLimit))
    }

    fun info(entry: TicketEntry): TicketInfo = TicketInfo(
        id = entry.id,
        player = entry.player,
        playerName = entry.playerName,
        subject = entry.subject,
        server = entry.server,
        status = TicketInfo.Status.valueOf(entry.status.name),
        moderator = entry.handler,
        createdAt = entry.createdAt,
        updatedAt = entry.updatedAt,
    )

    fun info(message: TicketMessage): TicketInfo.Message = TicketInfo.Message(
        id = message.id,
        author = message.author,
        staff = message.staff,
        content = message.content,
        createdAt = message.createdAt,
    )

    private fun announce(
        entry: TicketEntry,
        action: TicketUpdateEvent.Action,
        author: String,
        staff: Boolean,
        message: String?,
    ) {
        server.pluginManager.callEvent(TicketUpdateEvent(info(entry), action, author, staff, message))
    }

    private fun message(ticket: UUID, author: String, staff: Boolean, content: String, at: Long) = TicketMessage(
        id = UUID.randomUUID(),
        ticket = ticket,
        author = author,
        staff = staff,
        content = content,
        createdAt = at,
    )

    private fun notify(entry: TicketEntry, key: String, vararg extra: TagResolver) {
        threads.main {
            val online = server.getPlayer(entry.player) ?: return@main
            threads.forPlayer(online) {
                config().messages.send(
                    online,
                    key,
                    *extra,
                    Placeholder.unparsed("subject", entry.subject),
                    Placeholder.unparsed("moderator", ModeratorNames.display(entry.handler) ?: "-"),
                )
            }
        }
    }

    private fun alert(key: String, vararg resolvers: TagResolver) {
        val messages = config().messages
        for (staff in server.onlinePlayers) {
            if (!staff.hasPermission(RECEIVE)) continue
            threads.forPlayer(staff) { messages.send(staff, key, *resolvers) }
        }
        messages.send(server.consoleSender, key, *resolvers)
    }

    companion object {
        const val RECEIVE = "voxen.helpop.receive"
        const val MANAGE = "voxen.helpop.manage"

        private const val PAGE_SIZE = 8

        internal val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
