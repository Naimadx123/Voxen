package zone.vao.voxen.channel

import org.bukkit.Server
import org.bukkit.entity.Player
import zone.vao.voxen.RecipientProvider
import zone.vao.voxen.config.SoundConfig
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.hook.TeamHooks
import zone.vao.voxen.ignore.IgnoreService
import zone.vao.voxen.party.PartyService
import zone.vao.voxen.storage.PlayerData
import zone.vao.voxen.storage.PlayerDataService

class ChannelService(
    private val server: Server,
    private val config: () -> VoxenConfig,
    private val playerData: PlayerDataService,
    private val ignores: IgnoreService,
    private val parties: PartyService,
    private val teams: TeamHooks,
) {

    private val apiChannels = java.util.concurrent.ConcurrentHashMap<String, Channel>()
    private val recipientProviders = java.util.concurrent.ConcurrentHashMap<String, RecipientProvider>()

    fun channels(): Map<String, Channel> = config().channels + apiChannels

    fun channel(id: String): Channel? {
        val lower = id.lowercase()
        return config().channels[lower] ?: apiChannels[lower]
    }

    fun registerApiChannel(id: String, displayName: String, format: String): Boolean {
        val lower = id.lowercase()
        if (!lower.matches(Regex("[a-z0-9_-]+")) || config().channels.containsKey(lower)) return false
        apiChannels[lower] = Channel(
            id = lower,
            displayName = displayName,
            type = ChannelType.CUSTOM,
            enabled = true,
            defaultChannel = false,
            defaultActive = false,
            readOnly = false,
            crossServer = false,
            radius = -1,
            worlds = emptySet(),
            format = format,
            groupFormats = emptyMap(),
            worldFormats = emptyMap(),
            consoleFormat = null,
            externalFormat = null,
            aliases = emptyList(),
            quickPrefix = null,
            cooldownMillis = 0L,
            readPermission = null,
            writePermission = null,
            joinPermission = null,
            managePermission = null,
            emptyWarning = false,
            itemTags = true,
            scope = null,
            discord = false,
            discordFormat = null,
            sound = SoundConfig(null),
        )
        return true
    }

    fun unregisterApiChannel(id: String): Boolean {
        recipientProviders.remove(id.lowercase())
        return apiChannels.remove(id.lowercase()) != null
    }

    fun registerRecipients(channelId: String, provider: RecipientProvider): Boolean {
        if (channel(channelId) == null) return false
        recipientProviders[channelId.lowercase()] = provider
        return true
    }

    fun unregisterRecipients(channelId: String) {
        recipientProviders.remove(channelId.lowercase())
    }

    fun activeChannel(player: Player): Channel? {
        val data = playerData.get(player.uniqueId)
        data.activeChannel?.let { id ->
            val channel = channel(id)
            if (channel != null && channel.enabled && isJoined(channel, data) && channel.canRead(player)) return channel
        }
        return defaultActive(player)
    }

    fun defaultActive(player: Player): Channel? =
        channels().values.firstOrNull { it.enabled && it.defaultActive && it.type != ChannelType.PARTY && it.canRead(player) }
            ?: channels().values.firstOrNull { it.enabled && it.defaultChannel && it.type != ChannelType.PARTY && it.canRead(player) }

    fun setActive(player: Player, channel: Channel): Boolean {
        if (!channel.enabled || !channel.canJoin(player)) return false
        val data = playerData.get(player.uniqueId)
        data.activeChannel = channel.id
        data.joinedChannels += channel.id
        data.leftChannels -= channel.id
        playerData.save(data)
        return true
    }

    fun join(player: Player, channel: Channel): Boolean {
        if (!channel.enabled || !channel.canJoin(player)) return false
        val data = playerData.get(player.uniqueId)
        data.joinedChannels += channel.id
        data.leftChannels -= channel.id
        playerData.save(data)
        return true
    }

    fun leave(player: Player, channel: Channel): Boolean {
        val data = playerData.get(player.uniqueId)
        if (!isJoined(channel, data)) return false
        data.joinedChannels -= channel.id
        data.leftChannels += channel.id
        if (data.activeChannel.equals(channel.id, ignoreCase = true)) data.activeChannel = null
        playerData.save(data)
        return true
    }

    fun joinedChannels(player: Player): List<Channel> {
        val data = playerData.get(player.uniqueId)
        return channels().values.filter { it.enabled && isJoined(it, data) && it.canRead(player) }
    }

    fun recipients(sender: Player, channel: Channel): LinkedHashSet<Player> {
        val provider = recipientProviders[channel.id]
        val base: Collection<Player> = when {
            provider != null -> provider.recipients(sender).filter { it.isOnline }
            channel.type == ChannelType.PARTY -> parties.membersOnline(sender)
            else -> server.onlinePlayers.toList()
        }
        val bypassIgnore = sender.hasPermission(BYPASS_IGNORE)
        val bypassChatToggle = sender.hasPermission(BYPASS_CHAT_TOGGLE)
        val result = LinkedHashSet<Player>()
        for (recipient in base) {
            if (recipient.uniqueId != sender.uniqueId) {
                if (provider == null && channel.type != ChannelType.PARTY && !passesChannelRules(sender, recipient, channel)) continue
                if (!bypassChatToggle && !playerData.get(recipient.uniqueId).chatEnabled) continue
                if (!bypassIgnore && ignores.isIgnoring(recipient.uniqueId, sender.uniqueId)) continue
            }
            result += recipient
        }
        result += sender
        return result
    }

    fun readers(
        channel: Channel,
        senderUuid: java.util.UUID? = null,
        bypassIgnore: Boolean = false,
        bypassChatToggle: Boolean = false,
    ): List<Player> =
        server.onlinePlayers.filter { player ->
            channel.canRead(player) &&
                isJoined(channel, playerData.get(player.uniqueId)) &&
                channel.allowsWorld(player.world.name) &&
                (bypassChatToggle || playerData.get(player.uniqueId).chatEnabled) &&
                (senderUuid == null || bypassIgnore || !ignores.isIgnoring(player.uniqueId, senderUuid))
        }

    fun quickChannel(player: Player, message: String): Pair<Channel, String>? {
        if (!config().quickChatEnabled) return null
        val candidates = channels().values
            .filter { it.enabled && it.quickPrefix != null }
            .sortedByDescending { it.quickPrefix!!.length }
        for (channel in candidates) {
            val prefix = channel.quickPrefix!!
            if (message.length > prefix.length && message.startsWith(prefix) && channel.canWrite(player)) {
                val content = message.substring(prefix.length).trim()
                if (content.isNotEmpty()) return channel to content
            }
        }
        return null
    }

    private fun passesChannelRules(sender: Player, recipient: Player, channel: Channel): Boolean {
        if (!channel.canRead(recipient)) return false
        if (!isJoined(channel, playerData.get(recipient.uniqueId))) return false
        if (!channel.allowsWorld(recipient.world.name)) return false
        if (channel.type == ChannelType.WORLD && recipient.world.uid != sender.world.uid) return false
        if (channel.radius >= 0) {
            val sameWorld = recipient.world.uid == sender.world.uid
            val distanceSquared = if (sameWorld) recipient.location.distanceSquared(sender.location) else Double.MAX_VALUE
            if (!withinRadius(channel.radius, sameWorld, distanceSquared)) return false
        }
        val scope = channel.scope
        if (scope != null && !teams.sameTeam(scope, sender, recipient)) return false
        return true
    }

    companion object {
        const val BYPASS_IGNORE = "voxen.bypass.ignore"
        const val BYPASS_CHAT_TOGGLE = "voxen.bypass.chattoggle"

        fun isJoined(channel: Channel, data: PlayerData): Boolean {
            val id = channel.id
            if (data.joinedChannels.any { it.equals(id, ignoreCase = true) }) return true
            if (data.leftChannels.any { it.equals(id, ignoreCase = true) }) return false
            return channel.defaultChannel
        }

        fun localOnlyReason(type: ChannelType, radius: Int, scope: String?): String? = when {
            radius >= 0 -> "a radius"
            type == ChannelType.WORLD -> "type 'world'"
            type == ChannelType.PARTY -> "type 'party'"
            scope != null -> "scope '$scope'"
            else -> null
        }

        fun withinRadius(radius: Int, sameWorld: Boolean, distanceSquared: Double): Boolean {
            if (radius < 0) return true
            if (!sameWorld) return false
            return distanceSquared <= radius.toDouble() * radius.toDouble()
        }
    }
}
