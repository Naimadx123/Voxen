package zone.vao.voxen.storage

import zone.vao.voxen.moderation.MuteEntry
import zone.vao.voxen.party.PartyRecord
import java.util.UUID

interface PlayerStorage {
    fun loadPlayer(uuid: UUID): PlayerData?
    fun savePlayer(data: PlayerData)
    fun loadIgnores(uuid: UUID): Set<UUID>
    fun addIgnore(uuid: UUID, ignored: UUID)
    fun removeIgnore(uuid: UUID, ignored: UUID)
    fun loadMutes(): List<MuteEntry>
    fun saveMute(entry: MuteEntry)
    fun deleteMute(uuid: UUID, channel: String?)
    fun loadParties(): List<PartyRecord>
    fun saveParty(record: PartyRecord)
    fun deleteParty(id: UUID)
    fun addPartyMember(id: UUID, member: UUID)
    fun removePartyMember(id: UUID, member: UUID)
    fun logChat(entries: List<ChatLogEntry>)
    fun chatHistory(uuid: UUID, limit: Int): List<ChatLogEntry>

    fun chatContext(channel: String, at: Long, before: Int, after: Int): List<ChatLogEntry>
    fun purgeChatLog(before: Long)
    fun findByName(name: String): Pair<UUID, String>?

    fun saveMail(entry: MailEntry)

    fun saveMailIfRoom(entry: MailEntry, max: Int): Boolean
    fun mailFor(recipient: UUID, unreadOnly: Boolean): List<MailEntry>

    fun mailCount(recipient: UUID, unreadOnly: Boolean): Int
    fun markMailRead(recipient: UUID)
    fun deleteMail(recipient: UUID, id: UUID): Boolean
    fun clearMail(recipient: UUID): Int
    fun purgeMail(before: Long)
    fun saveReport(entry: ReportEntry)

    fun reports(statuses: Collection<ReportEntry.Status>, limit: Int): List<ReportEntry>
    fun report(id: UUID): ReportEntry?
    fun reportCount(statuses: Collection<ReportEntry.Status>): Int

    fun hasOpenReport(reporter: UUID, target: UUID): Boolean

    fun hasReportFor(messageId: UUID): Boolean
    fun updateReport(id: UUID, status: ReportEntry.Status, handler: String?, updatedAt: Long): Boolean
    fun deleteReport(id: UUID): Boolean

    fun purgeReports(before: Long)
    fun saveReportAction(entry: ReportAction)

    fun reportActions(report: UUID?, limit: Int): List<ReportAction>
    fun saveTicket(entry: TicketEntry)

    fun ticket(id: UUID): TicketEntry?
    fun tickets(statuses: Collection<TicketEntry.Status>, limit: Int): List<TicketEntry>

    fun ticketsOf(player: UUID, statuses: Collection<TicketEntry.Status>, limit: Int): List<TicketEntry>
    fun ticketCount(player: UUID, statuses: Collection<TicketEntry.Status>): Int
    fun updateTicket(id: UUID, status: TicketEntry.Status, handler: String?, updatedAt: Long): Boolean

    fun deleteTicket(id: UUID): Boolean
    fun saveTicketMessage(message: TicketMessage)

    fun ticketMessages(ticket: UUID, limit: Int): List<TicketMessage>
    fun purgeTickets(before: Long)
    fun saveStaffNote(entry: StaffNote)
    fun staffNotes(target: UUID, kind: StaffNote.Kind, since: Long): List<StaffNote>

    fun staffNoteCount(target: UUID, kind: StaffNote.Kind, since: Long): Int
    fun deleteStaffNote(id: UUID): Boolean
    fun purgeStaffNotes(before: Long, kind: StaffNote.Kind)
    fun close()
}
