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
    fun close()
}
