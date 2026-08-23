package zone.vao.voxen.moderation

import org.bukkit.Server
import zone.vao.voxen.event.PlayerMuteEvent
import zone.vao.voxen.event.PlayerUnmuteEvent
import zone.vao.voxen.network.BrokerMessage
import zone.vao.voxen.network.BrokerService
import zone.vao.voxen.storage.PlayerDataService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MuteService(
    private val server: Server,
    private val playerData: PlayerDataService,
) {

    @Volatile
    var remotePublisher: ((BrokerMessage) -> Unit)? = null

    @Volatile
    var globalChatMuted: Boolean = false
        private set

    private val mutedChannels = ConcurrentHashMap.newKeySet<String>()
    private val slowmodes = ConcurrentHashMap<String, Long>()
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

    fun setSlowmode(channelId: String, millis: Long) {
        if (millis > 0) slowmodes[channelId.lowercase()] = millis else slowmodes -= channelId.lowercase()
    }

    fun slowmode(channelId: String): Long = slowmodes[channelId.lowercase()] ?: 0L

    fun mute(entry: MuteEntry): Boolean {
        val event = PlayerMuteEvent(
            entry.uuid,
            entry.playerName,
            entry.channel,
            entry.moderator,
            entry.reason,
            entry.expiresAt,
        )
        server.pluginManager.callEvent(event)
        if (event.isCancelled) return false
        val applied = entry.copy(reason = event.reason, expiresAt = event.expiresAt)
        applyMute(applied)
        remotePublisher?.invoke(
            BrokerMessage(
                id = UUID.randomUUID().toString(),
                server = null,
                channel = applied.channel,
                sender = applied.moderator,
                component = null,
                content = applied.reason,
                type = BrokerService.TYPE_MUTE,
                target = applied.playerName,
                targetUuid = applied.uuid.toString(),
                expiresAt = applied.expiresAt,
                createdAt = applied.createdAt,
            )
        )
        return true
    }

    fun unmute(uuid: UUID, channel: String?, moderator: String? = null): Boolean {
        val name = holder(uuid, channel) ?: return false
        if (!allowUnmute(uuid, name, channel, all = false, moderator = moderator)) return false
        val removed = applyUnmute(uuid, channel)
        if (removed) broadcastUnmute(uuid, channel, all = false)
        return removed
    }

    fun unmuteAll(uuid: UUID, moderator: String? = null): Int {
        val name = mutesFor(uuid).firstOrNull()?.playerName ?: return 0
        if (!allowUnmute(uuid, name, null, all = true, moderator = moderator)) return 0
        val count = applyUnmuteAll(uuid)
        if (count > 0) broadcastUnmute(uuid, null, all = true)
        return count
    }

    private fun allowUnmute(
        uuid: UUID,
        name: String,
        channel: String?,
        all: Boolean,
        moderator: String?,
    ): Boolean {
        val event = PlayerUnmuteEvent(uuid, name, channel, all, moderator)
        server.pluginManager.callEvent(event)
        return !event.isCancelled
    }

    private fun holder(uuid: UUID, channel: String?): String? {
        val list = mutes[uuid] ?: return null
        return synchronized(list) { list.firstOrNull { matches(it, channel) }?.playerName }
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
        playerData.durable("Mute for ${entry.uuid}") { it.saveMute(entry) }
    }

    private fun applyUnmute(uuid: UUID, channel: String?): Boolean {
        val list = mutes[uuid] ?: return false
        val removed = synchronized(list) { list.removeAll { matches(it, channel) } }
        if (removed) playerData.durable("Unmute for $uuid") { it.deleteMute(uuid, channel) }
        return removed
    }

    private fun matches(entry: MuteEntry, channel: String?): Boolean =
        if (channel == null) entry.channel == null else entry.channel.equals(channel, ignoreCase = true)

    private fun applyUnmuteAll(uuid: UUID): Int {
        val list = mutes.remove(uuid) ?: return 0
        val entries = synchronized(list) { list.toList() }
        for (entry in entries) {
            playerData.durable("Unmute for ${entry.uuid}") { it.deleteMute(entry.uuid, entry.channel) }
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

    fun mutesFor(uuid: UUID): List<MuteEntry> {
        val list = mutes[uuid] ?: return emptyList()
        val now = System.currentTimeMillis()
        return synchronized(list) { list.filterNot { it.expired(now) } }
    }

    fun activeMutes(): List<MuteEntry> {
        val now = System.currentTimeMillis()
        return mutes.values.flatMap { list -> synchronized(list) { list.filterNot { it.expired(now) } } }
    }
}
