package zone.vao.voxen.party

import org.bukkit.Server
import org.bukkit.entity.Player
import zone.vao.voxen.PartyInfo
import zone.vao.voxen.config.PartyConfig
import zone.vao.voxen.event.PartyDisbandEvent
import zone.vao.voxen.event.PartyJoinEvent
import zone.vao.voxen.event.PartyLeaveEvent
import zone.vao.voxen.storage.PlayerDataService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PartyService(
    private val server: Server,
    private val playerData: PlayerDataService,
    private val partyConfig: () -> PartyConfig,
) {

    data class Invite(
        val partyId: UUID,
        val invitedBy: String,
        val expiresAt: Long,
    )

    sealed interface Outcome {
        data object Ok : Outcome
        data object Disabled : Outcome
        data object AlreadyInParty : Outcome
        data object NotInParty : Outcome
        data object NotLeader : Outcome
        data object PartyFull : Outcome
        data object NoInvite : Outcome
        data object TargetInParty : Outcome
        data object InvalidName : Outcome
    }

    private val parties = ConcurrentHashMap<UUID, PartyRecord>()
    private val byMember = ConcurrentHashMap<UUID, UUID>()
    private val invites = ConcurrentHashMap<UUID, Invite>()

    fun load() {
        playerData.async { storage ->
            parties.clear()
            byMember.clear()
            for (record in storage.loadParties()) {
                parties[record.id] = record
                for (member in record.members) byMember[member] = record.id
            }
        }
    }

    fun partyOf(uuid: UUID): PartyRecord? = byMember[uuid]?.let { parties[it] }

    fun create(leader: Player, name: String): Outcome {
        if (!partyConfig().enabled) return Outcome.Disabled
        if (partyOf(leader.uniqueId) != null) return Outcome.AlreadyInParty
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 32 || !trimmed.matches(Regex("[A-Za-z0-9_-]+"))) return Outcome.InvalidName
        val record = PartyRecord(UUID.randomUUID(), trimmed, leader.uniqueId, setOf(leader.uniqueId))
        parties[record.id] = record
        byMember[leader.uniqueId] = record.id
        playerData.async { it.saveParty(record) }
        return Outcome.Ok
    }

    fun disband(leader: Player): Outcome {
        val record = partyOf(leader.uniqueId) ?: return Outcome.NotInParty
        if (record.leader != leader.uniqueId) return Outcome.NotLeader
        server.pluginManager.callEvent(PartyDisbandEvent(snapshot(record)))
        parties.remove(record.id)
        for (member in record.members) byMember.remove(member)
        invites.entries.removeIf { it.value.partyId == record.id }
        playerData.async { it.deleteParty(record.id) }
        return Outcome.Ok
    }

    fun invite(sender: Player, target: Player): Outcome {
        if (!partyConfig().enabled) return Outcome.Disabled
        val record = partyOf(sender.uniqueId) ?: return Outcome.NotInParty
        if (record.leader != sender.uniqueId) return Outcome.NotLeader
        if (record.members.size >= partyConfig().maxMembers) return Outcome.PartyFull
        if (partyOf(target.uniqueId) != null) return Outcome.TargetInParty
        invites[target.uniqueId] = Invite(record.id, sender.name, System.currentTimeMillis() + partyConfig().inviteExpiryMillis)
        return Outcome.Ok
    }

    fun pendingInvite(target: UUID): Invite? {
        val invite = invites[target] ?: return null
        if (invite.expiresAt <= System.currentTimeMillis()) {
            invites.remove(target)
            return null
        }
        return invite
    }

    fun accept(target: Player): Outcome {
        val invite = pendingInvite(target.uniqueId) ?: return Outcome.NoInvite
        invites.remove(target.uniqueId)
        if (partyOf(target.uniqueId) != null) return Outcome.AlreadyInParty
        val record = parties[invite.partyId] ?: return Outcome.NoInvite
        if (record.members.size >= partyConfig().maxMembers) return Outcome.PartyFull
        val event = PartyJoinEvent(target, snapshot(record))
        server.pluginManager.callEvent(event)
        if (event.isCancelled) return Outcome.NoInvite
        val updated = record.copy(members = record.members + target.uniqueId)
        parties[record.id] = updated
        byMember[target.uniqueId] = record.id
        playerData.async { it.addPartyMember(record.id, target.uniqueId) }
        return Outcome.Ok
    }

    fun deny(target: Player): Outcome {
        val invite = pendingInvite(target.uniqueId) ?: return Outcome.NoInvite
        invites.remove(target.uniqueId)
        parties[invite.partyId]
        return Outcome.Ok
    }

    fun leave(member: Player): Outcome {
        val record = partyOf(member.uniqueId) ?: return Outcome.NotInParty
        if (record.leader == member.uniqueId) return disband(member)
        removeMember(record, member.uniqueId, PartyLeaveEvent.Reason.LEFT)
        return Outcome.Ok
    }

    fun kick(leader: Player, target: UUID): Outcome {
        val record = partyOf(leader.uniqueId) ?: return Outcome.NotInParty
        if (record.leader != leader.uniqueId) return Outcome.NotLeader
        if (target == leader.uniqueId || target !in record.members) return Outcome.NotInParty
        removeMember(record, target, PartyLeaveEvent.Reason.KICKED)
        return Outcome.Ok
    }

    fun transfer(leader: Player, target: UUID): Outcome {
        val record = partyOf(leader.uniqueId) ?: return Outcome.NotInParty
        if (record.leader != leader.uniqueId) return Outcome.NotLeader
        if (target !in record.members) return Outcome.NotInParty
        val updated = record.copy(leader = target)
        parties[record.id] = updated
        playerData.async { it.saveParty(updated) }
        return Outcome.Ok
    }

    fun membersOnline(member: Player): List<Player> {
        val record = partyOf(member.uniqueId) ?: return emptyList()
        return record.members.mapNotNull { server.getPlayer(it) }
    }

    fun clearInvites(uuid: UUID) {
        invites.remove(uuid)
    }

    private fun removeMember(record: PartyRecord, member: UUID, reason: PartyLeaveEvent.Reason) {
        val updated = record.copy(members = record.members - member)
        parties[record.id] = updated
        byMember.remove(member)
        playerData.async { it.removePartyMember(record.id, member) }
        server.pluginManager.callEvent(PartyLeaveEvent(member, snapshot(updated), reason))
    }

    private fun snapshot(record: PartyRecord): PartyInfo =
        PartyInfo(id = record.id, name = record.name, leader = record.leader, members = record.members)
}
