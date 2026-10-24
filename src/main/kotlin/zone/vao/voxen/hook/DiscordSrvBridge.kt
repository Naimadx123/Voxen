package zone.vao.voxen.hook

import github.scarsz.discordsrv.DiscordSRV
import github.scarsz.discordsrv.api.ListenerPriority
import github.scarsz.discordsrv.api.Subscribe
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePreProcessEvent
import github.scarsz.discordsrv.api.events.GameChatMessagePostProcessEvent
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent
import github.scarsz.discordsrv.dependencies.emoji.EmojiParser
import github.scarsz.discordsrv.dependencies.jda.api.entities.Member
import github.scarsz.discordsrv.dependencies.jda.api.entities.Role
import github.scarsz.discordsrv.util.DiscordUtil
import github.scarsz.discordsrv.util.MessageUtil
import github.scarsz.discordsrv.dependencies.kyori.adventure.text.Component as DiscordComponent
import github.scarsz.discordsrv.dependencies.kyori.adventure.text.serializer.gson.GsonComponentSerializer as DiscordGson
import github.scarsz.discordsrv.dependencies.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class DiscordSrvBridge @JvmOverloads internal constructor(
    private val logger: Logger,
    private val receiver: DiscordReceiver,
    private val gateway: DiscordSrvGateway = LiveDiscordSrvGateway,
) : DiscordForwarder {

    private val warnedChannels = ConcurrentHashMap.newKeySet<String>()
    private val plain = PlainTextComponentSerializer.plainText()
    private val gson = GsonComponentSerializer.gson()

    init {
        gateway.subscribe(this)
    }

    override fun forward(player: Player, message: String, channel: String?, preformatted: Boolean) {
        if (!gateway.ready() || message.isBlank()) return
        val target = channel ?: gateway.mainChannel() ?: return
        if (!mapped(target)) return
        gateway.process(player, message, target, ForwardedChat(preformatted))
    }

    @Subscribe(priority = ListenerPriority.LOWEST)
    fun onNativeChat(event: GameChatMessagePreProcessEvent) {
        val source = event.triggeringBukkitEvent ?: return
        if (source is ForwardedChat) return
        if (source.javaClass.name in NATIVE_CHAT_EVENTS) event.isCancelled = true
    }

    @Subscribe(priority = ListenerPriority.MONITOR)
    fun onMessageBody(event: GameChatMessagePreProcessEvent) {
        val source = event.triggeringBukkitEvent as? ForwardedChat ?: return
        if (!source.preformatted || event.isCancelled) return
        source.body = plain.serialize(event.messageComponent)
    }

    @Subscribe(priority = ListenerPriority.LOWEST)
    fun onFormattedChat(event: GameChatMessagePostProcessEvent) {
        val source = event.triggeringBukkitEvent as? ForwardedChat ?: return
        if (!source.preformatted || event.isCancelled) return
        val text = source.body?.let(gateway::prepare)
        if (text.isNullOrBlank() || event.channel == null || !mapped(event.channel)) {
            event.isCancelled = true
        } else {
            event.processedMessage = text
        }
    }

    @Subscribe(priority = ListenerPriority.HIGHEST)
    fun onDiscordMessage(event: DiscordGuildMessagePreProcessEvent) {
        if (event.isCancelled) return
        val channels = gateway.gameChannels(event.channel.id)
        if (channels.isEmpty() || !receiver.claims(channels)) return
        event.isCancelled = true
        runCatching { receiver.receive(snapshot(event, channels)) }
            .onFailure { logger.warning("Failed to relay a Discord message into Voxen: ${it.message}") }
    }

    private fun snapshot(event: DiscordGuildMessagePreProcessEvent, channels: Set<String>): DiscordMessage {
        val message = event.message
        val member = event.member
        val author = event.author
        return DiscordMessage(
            id = message.id,
            channelId = event.channel.id,
            channelName = event.channel.name,
            channelNames = channels,
            authorId = author.id,
            authorName = member?.effectiveName ?: author.effectiveName,
            username = author.name,
            roles = member?.let(gateway::roles).orEmpty().map { it.name },
            roleColor = member?.let(gateway::roleColor),
            linked = gateway.linked(author.id),
            replyTo = message.referencedMessage?.let { it.member?.effectiveName ?: it.author.effectiveName },
            content = gateway.inbound(message.contentRaw),
            attachments = message.attachments.map { DiscordMessage.Attachment(it.fileName, it.url) } +
                message.stickers.map { DiscordMessage.Attachment(it.name, it.iconUrl) },
            markdown = { text -> runCatching { gson.deserialize(gateway.markdown(text)) }.getOrNull() },
            reject = { runCatching { message.addReaction(REJECTED).queue({}, {}) } },
        )
    }

    private fun mapped(channel: String): Boolean {
        if (gateway.mapped(channel)) return true
        if (warnedChannels.add(channel.lowercase())) {
            logger.warning("DiscordSRV has no usable Channels mapping for '$channel'; its Voxen messages will not be forwarded.")
        }
        return false
    }

    override fun close() {
        gateway.unsubscribe(this)
        warnedChannels.clear()
    }

    private class ForwardedChat(val preformatted: Boolean) : Event(true) {
        var body: String? = null

        override fun getHandlers(): HandlerList = HANDLERS

        companion object {
            private val HANDLERS = HandlerList()
        }
    }

    private companion object {
        val NATIVE_CHAT_EVENTS = setOf(
            "org.bukkit.event.player.AsyncPlayerChatEvent",
            "io.papermc.paper.event.player.AsyncChatEvent",
        )
        const val REJECTED = "❌"
    }
}

internal interface DiscordSrvGateway {
    fun subscribe(listener: Any)
    fun unsubscribe(listener: Any)
    fun ready(): Boolean
    fun mainChannel(): String?
    fun mapped(channel: String): Boolean
    fun process(player: Player, message: String, channel: String, source: Event)
    fun prepare(message: String): String?
    fun gameChannels(discordChannelId: String): Set<String>
    fun roles(member: Member): List<Role>
    fun roleColor(member: Member): Int?
    fun linked(discordId: String): UUID?
    fun inbound(message: String): String
    fun markdown(message: String): String
}

private object LiveDiscordSrvGateway : DiscordSrvGateway {
    override fun subscribe(listener: Any) = DiscordSRV.api.subscribe(listener)

    override fun unsubscribe(listener: Any) {
        DiscordSRV.api.unsubscribe(listener)
    }

    override fun ready(): Boolean = DiscordSRV.isReady

    override fun mainChannel(): String? = DiscordSRV.getPlugin().mainChatChannel

    override fun mapped(channel: String): Boolean =
        DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName(channel) != null

    override fun process(player: Player, message: String, channel: String, source: Event) {
        DiscordSRV.getPlugin().processChatMessage(player, DiscordComponent.text(message), channel, false, source)
    }

    override fun prepare(message: String): String? {
        val plugin = DiscordSRV.getPlugin()
        var text = message
        text = if (DiscordSRV.config().getBoolean("DiscordChatChannelTranslateMentions")) {
            DiscordUtil.convertMentionsFromNames(text, plugin.mainGuild)
        } else text.replace("@", "@\u200B")
        for ((pattern, replacement) in plugin.gameRegexes) {
            text = pattern.matcher(text).replaceAll(replacement)
            if (text.isBlank()) return null
        }
        return text.takeIf { it.isNotBlank() }
    }

    override fun gameChannels(discordChannelId: String): Set<String> =
        DiscordSRV.getPlugin().channels.filterValues { it == discordChannelId }.keys.toSet()

    override fun roles(member: Member): List<Role> = DiscordSRV.getPlugin().getSelectedRoles(member)

    override fun roleColor(member: Member): Int? =
        DiscordUtil.getTopRoleWithCustomColor(member)?.colorRaw
            ?.takeIf { it != Role.DEFAULT_COLOR_RAW }
            ?.and(0xFFFFFF)

    override fun linked(discordId: String): UUID? =
        runCatching { DiscordSRV.getPlugin().accountLinkManager?.getUuid(discordId) }.getOrNull()

    override fun inbound(message: String): String {
        val config = DiscordSRV.config()
        var text = DiscordUtil.convertMentionsToNames(message.replace("\u001b", ""))
        text = when (config.getStringElse("DiscordChatChannelEmojiBehavior", "name").lowercase()) {
            "hide" -> EmojiParser.removeAllEmojis(text)
            "show" -> text
            else -> EmojiParser.parseToAliases(text)
        }
        for ((pattern, replacement) in DiscordSRV.getPlugin().discordRegexes) {
            text = pattern.matcher(text).replaceAll(replacement)
        }
        val limit = config.getIntElse("DiscordChatChannelTruncateLength", 0)
        return if (limit > 0 && text.length > limit) text.take(limit) else text
    }

    override fun markdown(message: String): String =
        DiscordGson.gson().serialize(MessageUtil.reserializeToMinecraftBasedOnConfig(message))
}
