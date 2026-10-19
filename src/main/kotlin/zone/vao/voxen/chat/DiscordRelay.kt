package zone.vao.voxen.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.StyleBuilderApplicable
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Server
import zone.vao.voxen.ChatMessageContext
import zone.vao.voxen.channel.Channel
import zone.vao.voxen.channel.ChannelService
import zone.vao.voxen.channel.ChannelType
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.event.DiscordMessageReceiveEvent
import zone.vao.voxen.hook.DiscordMessage
import zone.vao.voxen.hook.DiscordReceiver
import zone.vao.voxen.mention.MentionService
import zone.vao.voxen.moderation.MuteService
import zone.vao.voxen.moderation.WordFilter
import zone.vao.voxen.network.BrokerMessage
import zone.vao.voxen.network.BrokerService
import zone.vao.voxen.util.Components
import zone.vao.voxen.util.MessageFormats
import zone.vao.voxen.util.Threads
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class DiscordRelay(
    private val server: Server,
    private val config: () -> VoxenConfig,
    private val channels: ChannelService,
    private val mutes: MuteService,
    private val wordFilter: WordFilter,
    private val mentions: MentionService,
    private val decorators: ChatDecorators,
    private val broker: BrokerService,
    private val threads: Threads,
    private val logger: Logger,
) : DiscordReceiver {

    private val mm = MessageFormats.miniMessage
    private val plain = PlainTextComponentSerializer.plainText()
    private val gson = GsonComponentSerializer.gson()
    private val warned = ConcurrentHashMap.newKeySet<String>()

    private sealed interface Target {
        data object None : Target
        data class Owned(val channel: Channel) : Target
        data class Ambiguous(val channels: List<Channel>) : Target
    }

    private class Moderated(val text: String, val censored: Boolean)

    override fun claims(channelNames: Set<String>): Boolean = target(channelNames) != Target.None

    override fun receive(message: DiscordMessage) {
        val channel = when (val target = target(message.channelNames)) {
            Target.None -> return
            is Target.Ambiguous -> {
                warnAmbiguous(message, target.channels)
                return
            }
            is Target.Owned -> target.channel
        }
        val id = UUID.nameUUIDFromBytes("discord:${message.id}".toByteArray(Charsets.UTF_8))
        if (!broker.claim(id.toString())) return
        threads.main { deliver(channel, message, id) }
    }

    private fun target(channelNames: Set<String>): Target {
        val matched = channels.channels().values.filter { channel ->
            channel.enabled && channel.discord && channel.fromDiscordFormat != null &&
                channel.type != ChannelType.PARTY &&
                channelNames.any { it.equals(channel.discordChannel, ignoreCase = true) }
        }
        return when (matched.size) {
            0 -> Target.None
            1 -> Target.Owned(matched.single())
            else -> Target.Ambiguous(matched)
        }
    }

    private fun warnAmbiguous(message: DiscordMessage, matched: List<Channel>) {
        val ids = matched.map { it.id }.sorted()
        if (!warned.add("${message.channelId}:${ids.joinToString(",")}")) return
        logger.warning(
            "Discord channel #${message.channelName} maps to the Voxen channels ${ids.joinToString(", ")}, which all " +
                "take messages from Discord. Its messages are not forwarded until only one of them has a " +
                "from-discord-format (or discord-format) for it.",
        )
    }

    private fun deliver(channel: Channel, message: DiscordMessage, id: UUID) {
        val format = channel.fromDiscordFormat ?: return
        if (muted(channel, message)) {
            message.reject()
            return
        }
        val moderated = moderate(message.content) ?: run {
            message.reject()
            return
        }
        if (moderated.text.isBlank() && message.attachments.isEmpty()) return
        val event = DiscordMessageReceiveEvent(
            channel.id,
            id,
            message.id,
            message.channelId,
            message.authorId,
            message.authorName,
            message.linked,
            moderated.text,
        )
        server.pluginManager.callEvent(event)
        if (event.isCancelled) return
        val text = event.content.trim()
        if (text.isEmpty() && message.attachments.isEmpty()) return

        val body = when {
            text.isEmpty() -> null
            moderated.censored -> Component.text(text)
            else -> message.markdown(text) ?: Component.text(text)
        }
        val content = body?.let(plain::serialize).orEmpty()
        val rendered = render(channel, format, message, body)

        deliverLocal(channel, message, id, rendered, content)
        if (channel.crossServer) publish(channel, message, id, rendered, content)
    }

    private fun muted(channel: Channel, message: DiscordMessage): Boolean =
        mutes.globalChatMuted || mutes.isChannelMuted(channel.id) ||
            message.linked?.let { mutes.isMuted(it, channel.id) } == true

    private fun moderate(content: String): Moderated? {
        val moderation = config().moderation
        val checks = listOfNotNull(
            wordFilter::checkLinks.takeIf { moderation.linksAffectsDiscord },
            wordFilter::check.takeIf { moderation.filterAffectsDiscord },
        )
        var text = content
        var censored = false
        for (check in checks) {
            when (val result = check(text)) {
                WordFilter.Result.Blocked -> return null
                is WordFilter.Result.Censored -> {
                    text = result.content
                    censored = true
                }
                WordFilter.Result.Clean -> Unit
            }
        }
        return Moderated(text, censored)
    }

    private fun render(channel: Channel, format: String, message: DiscordMessage, body: Component?): Component {
        val messages = config().messages
        val console = server.consoleSender
        val parts = listOfNotNull(body) + message.attachments.map { attachment ->
            messages.line(
                console,
                "discord-attachment",
                Placeholder.unparsed("name", attachment.name),
                Placeholder.unparsed("url", attachment.url),
            )
        }
        val optional = mapOf(
            "role" to message.roles.firstOrNull().orEmpty(),
            "roles" to message.roles.joinToString(", "),
            "reply_to" to message.replyTo.orEmpty(),
            "linked_player" to message.linked?.let { server.getOfflinePlayer(it).name }.orEmpty(),
        )
        val roleColor = listOfNotNull<StyleBuilderApplicable>(message.roleColor?.let(TextColor::color))
        return Components.tidy(
            mm.deserialize(
                dropEmpty(format, optional),
                TagResolver.resolver(optional.map { (name, value) -> Placeholder.unparsed(name, value) }),
                Placeholder.component("message", Component.join(JoinConfiguration.separator(Component.space()), parts)),
                Placeholder.unparsed("author", message.authorName),
                Placeholder.unparsed("username", message.username),
                Placeholder.unparsed("user_id", message.authorId),
                Placeholder.styling("role_color", *roleColor.toTypedArray()),
                Placeholder.unparsed("discord_channel", message.channelName),
                Placeholder.parsed("channel", channel.displayName),
                Placeholder.unparsed("channel_id", channel.id),
                Placeholder.unparsed("server", config().serverName),
            )
        )
    }

    private fun deliverLocal(channel: Channel, message: DiscordMessage, id: UUID, rendered: Component, content: String) {
        val mentioned = if (content.isNotEmpty() && config().mentions.enabled) mentions.mentionedNames(content) else emptySet()
        val context = ChatMessageContext(
            messageId = id,
            senderId = message.linked,
            senderName = message.authorName,
            serverId = config().network.serverId,
            channelId = channel.id,
            content = content,
            isRemote = false,
        )
        for (reader in channels.readers(channel, message.linked)) {
            var delivered = rendered
            val notify = reader.name.lowercase() in mentioned && mentions.accepts(reader)
            if (notify) delivered = mentions.highlight(delivered, reader)
            delivered = decorators.apply(context, null, reader, delivered)
            threads.forPlayer(reader) {
                reader.sendMessage(delivered)
                if (notify) mentions.notify(reader)
            }
        }
        server.consoleSender.sendMessage(rendered)
    }

    private fun publish(channel: Channel, message: DiscordMessage, id: UUID, rendered: Component, content: String) {
        broker.publish(
            BrokerMessage(
                id = id.toString(),
                server = config().network.serverId,
                channel = channel.id,
                sender = message.authorName,
                component = gson.serialize(rendered),
                content = content.ifEmpty { null },
                chatContent = content,
                mm = MiniMessage.miniMessage().serialize(rendered),
                senderUuid = message.linked?.toString(),
                flags = FLAG,
            )
        )
    }

    companion object {
        const val FLAG = "discord"

        private const val GONE = '\u0000'
        private val OPTIONAL = Regex("<(prefix|suffix|world|display_name|group|role|roles|reply_to|linked_player)>")
        private val GAP = Regex(" *$GONE[ $GONE]*")

        fun dropEmpty(format: String, values: Map<String, String>): String {
            val marked = OPTIONAL.replace(format) { match ->
                if (values[match.groupValues[1]].isNullOrBlank()) GONE.toString() else match.value
            }
            return GAP.replace(marked) { match ->
                val edge = match.range.first == 0 || match.range.last == marked.lastIndex
                if (!edge && ' ' in match.value) " " else ""
            }
        }
    }
}
