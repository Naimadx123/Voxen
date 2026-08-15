package zone.vao.voxen.moderation

import zone.vao.voxen.network.BrokerMessage
import zone.vao.voxen.network.BrokerService
import zone.vao.voxen.storage.PlayerDataService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MuteService(
    private val playerData: PlayerDataService,
) {

    @Volatile
    var remotePublisher: ((BrokerMessage) -> Unit)? = null

    @Volatile
    var globalChatMuted: Boolean = false
        private set

    private val mutedChannels = ConcurrentHashMap.newKeySet<String>()
    private val mutes = ConcurrentHashMap<UUID, MutableList<MuteEntry>>()

    fun load() {
        playerData.async { storage ->
            val loaded = storage.loadMutes()
            mutes.clear()
            for (entry in loaded) {
                if (entry.expired()) {
                    storage.deleteMute(entry.uuid, entry.channel)
                    continue
                }
                mutes.getOrPut(entry.uuid) { mutableListOf() } += entry
            }
        }
    }

    fun setGlobalChatMuted(muted: Boolean) {
        globalChatMuted = muted
    }

    fun setChannelMuted(channelId: String, muted: Boolean) {
        if (muted) mutedChannels += channelId.lowercase() else mutedChannels -= channelId.lowercase()
    }

    fun isChannelMuted(channelId: String): Boolean = channelId.lowercase() in mutedChannels

    fun mute(entry: MuteEntry) {
        applyMute(entry)
        remotePublisher?.invoke(
            BrokerMessage(
                id = UUID.randomUUID().toString(),
                server = null,
                channel = entry.channel,
                sender = entry.moderator,
                component = null,
                content = entry.reason,
                type = BrokerService.TYPE_MUTE,
                target = entry.playerName,
                targetUuid = entry.uuid.toString(),
                expiresAt = entry.expiresAt,
                createdAt = entry.createdAt,
            )
        )
    }

    fun unmute(uuid: UUID, channel: String?): Boolean {
        val removed = applyUnmute(uuid, channel)
        if (removed) broadcastUnmute(uuid, channel, all = false)
        return removed
    }

    fun unmuteAll(uuid: UUID): Int {
        val count = applyUnmuteAll(uuid)
        if (count > 0) broadcastUnmute(uuid, null, all = true)
        return count
    }

    fun handleRemote(message: BrokerMessage) {
        val uuid = runCatching { UUID.fromString(message.targetUuid) }.getOrNull() ?: return
        when (message.type) {
            BrokerService.TYPE_MUTE -> applyMute(
                MuteEntry(
                    uuid = uuid,
                    playerName = message.target ?: return,
                    channel = message.channel,
                    reason = message.content,
                    moderator = message.sender ?: return,
                    expiresAt = message.expiresAt,
                    createdAt = message.createdAt ?: System.currentTimeMillis(),
                )
            )
            BrokerService.TYPE_UNMUTE ->
                if (message.status == "all") applyUnmuteAll(uuid) else applyUnmute(uuid, message.channel)
        }
    }

    private fun applyMute(entry: MuteEntry) {
        val list = mutes.getOrPut(entry.uuid) { mutableListOf() }
        synchronized(list) {
            list.removeAll { it.channel.equals(entry.channel, ignoreCase = true) || (it.channel == null && entry.channel == null) }
            list += entry
        }
        playerData.async { it.saveMute(entry) }
    }

    private fun applyUnmute(uuid: UUID, channel: String?): Boolean {
        val list = mutes[uuid] ?: return false
        val removed = synchronized(list) {
            list.removeAll {
                (channel == null && it.channel == null) || (channel != null && it.channel.equals(channel, ignoreCase = true))
            }
        }
        if (removed) playerData.async { it.deleteMute(uuid, channel) }
        return removed
    }

    private fun applyUnmuteAll(uuid: UUID): Int {
        val list = mutes.remove(uuid) ?: return 0
        val entries = synchronized(list) { list.toList() }
        for (entry in entries) {
            playerData.async { it.deleteMute(entry.uuid, entry.channel) }
        }
        return entries.size
    }

    private fun broadcastUnmute(uuid: UUID, channel: String?, all: Boolean) {
        remotePublisher?.invoke(
            BrokerMessage(
                id = UUID.randomUUID().toString(),
                server = null,
                channel = channel,
                sender = null,
                component = null,
                type = BrokerService.TYPE_UNMUTE,
                targetUuid = uuid.toString(),
                status = if (all) "all" else null,
            )
        )
    }

    fun activeMute(uuid: UUID, channelId: String?): MuteEntry? {
        val list = mutes[uuid] ?: return null
        val now = System.currentTimeMillis()
        synchronized(list) {
            val expired = list.filter { it.expired(now) }
            if (expired.isNotEmpty()) {
                list.removeAll(expired)
                for (entry in expired) playerData.async { it.deleteMute(entry.uuid, entry.channel) }
            }
            return list.firstOrNull { it.applies(channelId) }
        }
    }

    fun isMuted(uuid: UUID, channelId: String?): Boolean = activeMute(uuid, channelId) != null

    fun activeMutes(): List<MuteEntry> {
        val now = System.currentTimeMillis()
        return mutes.values.flatMap { list -> synchronized(list) { list.filterNot { it.expired(now) } } }
    }
}
