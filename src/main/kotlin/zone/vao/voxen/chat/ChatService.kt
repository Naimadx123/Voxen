package zone.vao.voxen.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Server
import org.bukkit.entity.Player
import zone.vao.voxen.channel.Channel
import zone.vao.voxen.channel.ChannelService
import zone.vao.voxen.config.TagsConfig
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.event.ChatMessageDeliveredEvent
import zone.vao.voxen.event.ChatMessageSendEvent
import zone.vao.voxen.hook.HookManager
import zone.vao.voxen.item.ItemTags
import zone.vao.voxen.mention.MentionService
import zone.vao.voxen.moderation.MuteService
import zone.vao.voxen.moderation.SpamGuard
import zone.vao.voxen.moderation.WordFilter
import zone.vao.voxen.storage.ChatLogEntry
import zone.vao.voxen.storage.PlayerDataService
import zone.vao.voxen.tags.ContentRenderer
import zone.vao.voxen.tags.Replacements
import zone.vao.voxen.util.Durations
import zone.vao.voxen.util.Threads
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatService(
    private val server: Server,
    private val config: () -> VoxenConfig,
    private val channels: ChannelService,
    private val formats: FormatService,
    private val renderer: ContentRenderer,
    private val mutes: MuteService,
    private val spamGuard: SpamGuard,
    private val wordFilter: WordFilter,
    private val mentions: MentionService,
    private val playerData: PlayerDataService,
    private val hooks: HookManager,
    private val threads: Threads,
) {

    @Volatile
    var remotePublisher: ((Channel, Player, Component, String) -> Unit)? = null

    private val plain = PlainTextComponentSerializer.plainText()
    private val lastItemShare = ConcurrentHashMap<UUID, Long>()

    class Outgoing(
        val player: Player,
        val channel: Channel,
        val content: String,
        val message: Component,
        val formatted: Component,
        val unfiltered: Component?,
        val console: Component,
        val recipients: List<Player>,
        val mentionedNames: Set<String>,
        val mentionsAllowed: Boolean,
        val networkFormatted: Component?,
    )

    fun chat(player: Player, raw: String): Boolean {
        val out = prepareChat(player, raw) ?: return false
        deliver(out)
        return true
    }

    fun prepareChat(player: Player, raw: String): Outgoing? {
        val picked = threads.awaitPlayer(player) {
            val quick = channels.quickChannel(player, raw)
            if (quick != null) {
                quick
            } else {
                val active = channels.activeChannel(player)
                if (active == null) {
                    config().messages.send(player, "no-channel")
                    null
                } else {
                    active to raw
                }
            }
        } ?: return null
        return prepare(player, picked.first, picked.second)
    }

    fun send(player: Player, channel: Channel, rawContent: String): Boolean {
        val out = prepare(player, channel, rawContent) ?: return false
        deliver(out)
        return true
    }

    fun prepare(player: Player, channel: Channel, rawContent: String): Outgoing? {
        // reading the sender (world, permissions, placeholders) only happens on the sender's own thread
        val snapshot = threads.awaitPlayer(player) { validate(player, channel, rawContent) } ?: return null
        // word filtering is plain text work, so it stays off the region thread
        val filtered = filter(player, snapshot) ?: return null
        return threads.awaitPlayer(player) { build(player, channel, filtered.content, filtered.uncensored) }
    }

    private class Snapshot(val content: String, val bypassLinks: Boolean, val bypassFilter: Boolean)

    private class Filtered(val content: String, val uncensored: String?)

    private fun validate(player: Player, channel: Channel, rawContent: String): Snapshot? {
        val messages = config().messages
        val channelName = Placeholder.parsed("channel", channel.displayName)

        if (!channel.enabled) {
            messages.send(player, "channel-disabled", channelName)
            return null
        }
        if (!channel.allowsWorld(player.world.name)) {
            messages.send(player, "channel-wrong-world", channelName)
            return null
        }
        if (channel.readOnly && !channel.canManage(player)) {
            messages.send(player, "channel-read-only", channelName)
            return null
        }
        if (!channel.canWrite(player)) {
            messages.send(player, "channel-no-write", channelName)
            return null
        }
        if (mutes.globalChatMuted && !player.hasPermission(BYPASS_GLOBAL_MUTE)) {
            messages.send(player, "chat-muted")
            return null
        }
        if (mutes.isChannelMuted(channel.id) && !player.hasPermission(BYPASS_CHANNEL_MUTE)) {
            messages.send(player, "channel-muted", channelName)
            return null
        }
        val mute = mutes.activeMute(player.uniqueId, channel.id)
        if (mute != null) {
            val remaining = mute.expiresAt?.let { Durations.humanize(it - System.currentTimeMillis()) } ?: messages.raw(player, "mute-permanent")
            messages.send(
                player,
                "you-are-muted",
                Placeholder.unparsed("reason", mute.reason ?: messages.raw(player, "mute-no-reason")),
                Placeholder.unparsed("remaining", remaining),
            )
            return null
        }

        val maxLength = config().moderation.maxLength
        if (maxLength > 0 && rawContent.length > maxLength) {
            messages.send(player, "message-too-long", Placeholder.unparsed("max", maxLength.toString()))
            return null
        }

        when (val result = spamGuard.check(
            uuid = player.uniqueId,
            channelId = channel.id,
            channelCooldownMillis = maxOf(channel.cooldownMillis, slowmodeFor(channel)),
            content = rawContent,
            bypassCooldown = player.hasPermission(BYPASS_COOLDOWN),
            bypassRepeat = player.hasPermission(BYPASS_SPAM),
        )) {
            is SpamGuard.Result.Cooldown -> {
                messages.send(player, "chat-cooldown", Placeholder.unparsed("remaining", Durations.humanize(result.remainingMillis)))
                return null
            }
            SpamGuard.Result.Repeat -> {
                messages.send(
                    player,
                    "chat-repeat",
                    Placeholder.unparsed("threshold", config().moderation.similarityThresholdPercent),
                )
                return null
            }
            SpamGuard.Result.Flood -> {
                messages.send(player, "chat-flood")
                return null
            }
            SpamGuard.Result.Ok -> Unit
        }

        val content = if (hooks.papi != null) applyPapi(player, rawContent) else rawContent
        return Snapshot(content, player.hasPermission(BYPASS_LINKS), player.hasPermission(BYPASS_FILTER))
    }

    private fun filter(player: Player, snapshot: Snapshot): Filtered? {
        val messages = config().messages
        var content = snapshot.content
        var uncensored: String? = null
        if (!snapshot.bypassLinks) {
            when (val result = wordFilter.checkLinks(content)) {
                WordFilter.Result.Blocked -> {
                    messages.send(player, "message-has-link")
                    return null
                }
                is WordFilter.Result.Censored -> {
                    uncensored = content
                    content = result.content
                }
                WordFilter.Result.Clean -> Unit
            }
        }
        if (!snapshot.bypassFilter) {
            when (val result = wordFilter.check(content)) {
                WordFilter.Result.Blocked -> {
                    messages.send(player, "message-blocked")
                    return null
                }
                is WordFilter.Result.Censored -> {
                    uncensored = uncensored ?: content
                    content = result.content
                }
                WordFilter.Result.Clean -> Unit
            }
        }

        val emotes = config().emotes
        return Filtered(
            emotes.apply(content, player::hasPermission),
            uncensored?.let { emotes.apply(it, player::hasPermission) },
        )
    }

    private fun build(player: Player, channel: Channel, filtered: String, censored: String?): Outgoing? {
        var content = filtered
        var uncensored = censored
        val recipients = channels.recipients(player, channel)
        val event = ChatMessageSendEvent(player, channel.id, content, recipients)
        server.pluginManager.callEvent(event)
        if (event.isCancelled) return null
        if (event.content != content) uncensored = null
        content = event.content
        val finalRecipients = event.recipients.filter { it.isOnline }

        val extraResolvers = itemResolvers(player, channel, content) +
            replacementResolvers(player, content) +
            Placeholder.unparsed("server", config().serverName) +
            listOfNotNull(hooks.miniPlaceholders?.gated(player) { name ->
                player.hasPermission(MINI_PERMISSION) || player.hasPermission("$MINI_PERMISSION.$name")
            })
        val message = renderer.render(content, player::hasPermission, extraResolvers, isPermissionSet = player::isPermissionSet)
        val format = formats.formatFor(channel, player)
        val formatted = formats.render(format, player, channel, message)
        val unfiltered = uncensored
            ?.takeIf { finalRecipients.any(::seesUnfiltered) }
            ?.let { formats.render(format, player, channel, renderer.render(it, player::hasPermission, extraResolvers, isPermissionSet = player::isPermissionSet)) }

        val mentionedNames = if (config().mentions.enabled) mentions.mentionedNames(content) else emptySet()
        val mentionsAllowed = mentionedNames.isNotEmpty() &&
            player.hasPermission(MENTION_PERMISSION) &&
            mentions.tryUse(player)

        val networkFormatted = if (channel.crossServer && config().tags.mode == TagsConfig.UnauthorizedMode.ESCAPE) {
            val stripped = renderer.render(content, player::hasPermission, extraResolvers, TagsConfig.UnauthorizedMode.STRIP, player::isPermissionSet)
            formats.render(format, player, channel, stripped)
        } else {
            null
        }

        val console = formats.render(channel.consoleFormat ?: format, player, channel, message)

        return Outgoing(
            player,
            channel,
            content,
            message,
            formatted,
            unfiltered,
            console,
            finalRecipients,
            mentionedNames,
            mentionsAllowed,
            networkFormatted,
        )
    }

    fun viewFor(out: Outgoing, recipient: Player): Component {
        var delivered = if (out.unfiltered != null && seesUnfiltered(recipient)) out.unfiltered else out.formatted
        if (isMentioned(out, recipient)) delivered = mentions.highlight(delivered, recipient)
        return delivered
    }

    fun consoleView(out: Outgoing): Component = out.console

    fun effectsFor(out: Outgoing, recipient: Player) {
        val mentioned = isMentioned(out, recipient)
        val sound = out.channel.sound.sound?.takeIf { recipient.uniqueId != out.player.uniqueId }
        if (!mentioned && sound == null) return
        threads.forPlayer(recipient) {
            if (mentioned) mentions.notify(recipient)
            sound?.let { recipient.playSound(it) }
        }
    }

    fun finish(out: Outgoing) {
        threads.main {
            if (out.channel.emptyWarning && out.recipients.none { it.uniqueId != out.player.uniqueId }) {
                config().messages.send(out.player, "channel-empty", Placeholder.parsed("channel", out.channel.displayName))
            }
            server.pluginManager.callEvent(
                ChatMessageDeliveredEvent(out.player, out.channel.id, out.content, out.formatted, out.recipients),
            )
            if (out.channel.discord) {
                val custom = out.channel.discordFormat
                    ?.let { plain.serialize(formats.render(it, out.player, out.channel, out.message)) }
                hooks.discord.forward(out.player, custom ?: plain.serialize(out.message), preformatted = custom != null)
            }
        }

        if (config().moderation.historyEnabled) {
            val entry = ChatLogEntry(
                uuid = out.player.uniqueId,
                playerName = out.player.name,
                channel = out.channel.id,
                content = out.content,
                server = config().network.serverId,
                createdAt = System.currentTimeMillis(),
            )
            playerData.logChat(entry)
        }

        if (out.channel.crossServer) {
            val mentionContent = if (out.mentionsAllowed) plain.serialize(out.message) else ""
            remotePublisher?.invoke(out.channel, out.player, out.networkFormatted ?: out.formatted, mentionContent)
        }
    }

    private fun deliver(out: Outgoing) {
        for (recipient in out.recipients) {
            val view = viewFor(out, recipient)
            threads.forPlayer(recipient) { recipient.sendMessage(view) }
            effectsFor(out, recipient)
        }
        server.consoleSender.sendMessage(consoleView(out))
        finish(out)
    }

    fun broadcast(channel: Channel, message: Component): Boolean {
        threads.main {
            for (reader in channels.readers(channel)) {
                threads.forPlayer(reader) { reader.sendMessage(message) }
            }
        }
        server.consoleSender.sendMessage(message)
        return true
    }

    fun deliverRemote(
        channelId: String,
        message: Component,
        content: String? = null,
        senderUuid: UUID? = null,
        bypassIgnore: Boolean = false,
        bypassChatToggle: Boolean = false,
    ) {
        val channel = channels.channel(channelId) ?: return
        if (!channel.enabled || !channel.crossServer) return
        val mentioned = if (!content.isNullOrEmpty() && config().mentions.enabled) mentions.mentionedNames(content) else emptySet()
        threads.main {
            for (reader in channels.readers(channel, senderUuid, bypassIgnore, bypassChatToggle)) {
                var delivered = message
                val notify = reader.name.lowercase() in mentioned && mentions.accepts(reader)
                if (notify) delivered = mentions.highlight(delivered, reader)
                threads.forPlayer(reader) {
                    reader.sendMessage(delivered)
                    if (notify) mentions.notify(reader)
                }
            }
        }
        server.consoleSender.sendMessage(message)
    }

    fun forget(uuid: UUID) {
        spamGuard.forget(uuid)
        mentions.forget(uuid)
        lastItemShare.remove(uuid)
    }

    private fun applyPapi(player: Player, content: String): String {
        if (!content.contains('%')) return content
        val base = player.hasPermission(PAPI_PERMISSION)
        return PAPI_PATTERN.replace(content) { match ->
            val allowed = base || player.hasPermission("$PAPI_PERMISSION.${match.groupValues[1].lowercase()}")
            if (allowed) hooks.applyPlaceholders(player, match.value).replace('§', '&') else match.value
        }
    }

    private fun slowmodeFor(channel: Channel): Long =
        if (config().moderation.slowmodeEnabled) mutes.slowmode(channel.id) else 0L

    private fun isMentioned(out: Outgoing, recipient: Player): Boolean =
        out.mentionsAllowed && recipient.uniqueId != out.player.uniqueId &&
            recipient.name.lowercase() in out.mentionedNames && mentions.accepts(recipient)

    private fun seesUnfiltered(recipient: Player): Boolean =
        recipient.hasPermission(FILTER_TOGGLE) && !playerData.get(recipient.uniqueId).filterEnabled

    private fun itemResolvers(player: Player, channel: Channel, content: String): List<TagResolver> {
        val itemShare = config().itemShare
        return if (
            itemShare.enabled &&
            channel.itemTags &&
            player.hasPermission(ItemTags.PERMISSION) &&
            ItemTags.containsItemTag(content) &&
            tryItemShare(player, itemShare.cooldownMillis)
        ) {
            val emptyLabel = config().messages.line(player, "item-empty")
            listOf(ItemTags.resolvers(player, emptyLabel))
        } else {
            emptyList()
        }
    }

    private fun replacementResolvers(player: Player, content: String): List<TagResolver> {
        val replacements = config().tags.replacements
        if (replacements.isEmpty() || !content.contains('<')) return emptyList()
        return replacements.values.mapNotNull { rule ->
            if (!Replacements.permitted(rule, player)) return@mapNotNull null
            val used = rule.names().filter { content.contains("<$it>", ignoreCase = true) }
            if (used.isEmpty()) return@mapNotNull null
            val rendered = Replacements.render(hooks, rule, player)
            TagResolver.resolver(used.map { Placeholder.component(it, rendered) })
        }
    }

    private fun tryItemShare(player: Player, cooldownMillis: Long): Boolean {
        if (cooldownMillis <= 0 || player.hasPermission(BYPASS_ITEM_COOLDOWN)) {
            lastItemShare[player.uniqueId] = System.currentTimeMillis()
            return true
        }
        val now = System.currentTimeMillis()
        val last = lastItemShare[player.uniqueId] ?: 0L
        if (now - last < cooldownMillis) {
            config().messages.send(
                player,
                "item-share-cooldown",
                Placeholder.unparsed("remaining", Durations.humanize(last + cooldownMillis - now)),
            )
            return false
        }
        lastItemShare[player.uniqueId] = now
        return true
    }

    private companion object {
        const val BYPASS_GLOBAL_MUTE = "voxen.bypass.mutechat"
        const val BYPASS_CHANNEL_MUTE = "voxen.bypass.mutechannel"
        const val BYPASS_COOLDOWN = "voxen.bypass.cooldown"
        const val BYPASS_SPAM = "voxen.bypass.spam"
        const val BYPASS_FILTER = "voxen.bypass.filter"
        const val BYPASS_LINKS = "voxen.bypass.links"
        const val FILTER_TOGGLE = "voxen.filter.toggle"
        const val BYPASS_ITEM_COOLDOWN = "voxen.bypass.item-cooldown"
        const val MENTION_PERMISSION = "voxen.chat.mention"
        const val PAPI_PERMISSION = "voxen.chat.papi"
        const val MINI_PERMISSION = "voxen.chat.miniplaceholders"
        val PAPI_PATTERN = Regex("%([^%\\s]+)%")
    }
}
