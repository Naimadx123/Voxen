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
    fun purgeChatLog(before: Long)
    fun saveMail(entry: MailEntry)
    fun mailFor(recipient: UUID, unreadOnly: Boolean): List<MailEntry>
    fun markMailRead(recipient: UUID)
    fun deleteMail(recipient: UUID, id: UUID): Boolean
    fun clearMail(recipient: UUID): Int
    fun purgeMail(before: Long)
    fun saveStaffNote(entry: StaffNote)
    fun staffNotes(target: UUID, kind: StaffNote.Kind, since: Long): List<StaffNote>
    fun deleteStaffNote(id: UUID): Boolean
    fun purgeStaffNotes(before: Long, kind: StaffNote.Kind)
    fun close()
}
