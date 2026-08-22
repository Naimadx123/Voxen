package zone.vao.voxen.system

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import zone.vao.voxen.channel.ChannelService
import zone.vao.voxen.chat.ChatService
import zone.vao.voxen.chat.FormatService
import zone.vao.voxen.config.SystemMessagesConfig
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.hook.DiscordHooks
import zone.vao.voxen.network.BrokerMessage
import zone.vao.voxen.network.BrokerService
import zone.vao.voxen.presence.PresenceService
import zone.vao.voxen.util.Vanish
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SystemMessageService(
    private val config: () -> VoxenConfig,
    private val channels: ChannelService,
    private val chat: ChatService,
    private val formats: FormatService,
    private val discord: () -> DiscordHooks,
    private val presence: PresenceService,
) {

    @Volatile
    var remotePublisher: ((BrokerMessage) -> Unit)? = null

    @Volatile
    var schedule: ((Long, () -> Unit) -> Unit)? = null

    private val leaving = ConcurrentHashMap<UUID, Pending>()

    private val plain = PlainTextComponentSerializer.plainText()

    private val codec = GsonComponentSerializer.gson()

    private class Pending(
        val component: Component,
        val event: SystemMessagesConfig.Event,
        val player: Player,
    )

    fun handles(kind: SystemMessagesConfig.Kind): Boolean = config().systemMessages.handles(kind)

    fun hidden(player: Player): Boolean =
        config().systemMessages.respectVanish && Vanish.hidden(player)

    fun join(player: Player) {
        val rejoined = leaving.remove(player.uniqueId) != null
        if (hidden(player)) return
        val from = previousServer(player.uniqueId)
        if (from == null && rejoined) return
        val kind = when {
            from != null -> SystemMessagesConfig.Kind.SERVER_SWITCH
            !player.hasPlayedBefore() -> SystemMessagesConfig.Kind.FIRST_JOIN
            else -> SystemMessagesConfig.Kind.JOIN
        }
        val extra = if (from == null) emptyArray() else arrayOf<TagResolver>(
            Placeholder.unparsed("from", from),
            Placeholder.unparsed("to", config().network.serverId),
        )
        announce(kind, player, *extra)
    }

    fun quit(player: Player) {
        if (hidden(player)) return
        val event = config().systemMessages.event(SystemMessagesConfig.Kind.QUIT) ?: return
        val component = render(event, player)
        if (event.delayMillis <= 0L) {
            publish(component, event, player)
            return
        }
        val uuid = player.uniqueId
        leaving[uuid] = Pending(component, event, player)
        val scheduler = schedule ?: run {
            publish(component, event, player)
            leaving.remove(uuid)
            return
        }
        scheduler(event.delayMillis) {
            leaving.remove(uuid)?.let { pending -> publish(pending.component, pending.event, pending.player) }
        }
    }

    fun cancelQuit(uuid: UUID) {
        leaving.remove(uuid)
    }

    fun announce(kind: SystemMessagesConfig.Kind, player: Player, vararg extra: TagResolver) {
        if (hidden(player)) return
        val event = config().systemMessages.event(kind) ?: return
        publish(render(event, player, *extra), event, player)
    }

    fun handleRemote(message: BrokerMessage) {
        val channelId = message.channel ?: return
        val serialized = message.component ?: return
        val channel = channels.channel(channelId)?.takeIf { it.enabled } ?: return
        val component = runCatching { codec.deserialize(serialized) }.getOrNull() ?: return
        chat.broadcast(channel, component)
    }

    private fun render(event: SystemMessagesConfig.Event, player: Player, vararg extra: TagResolver): Component =
        formats.renderSystem(event.format, player, *extra)

    private fun publish(component: Component, event: SystemMessagesConfig.Event, source: Player?) {
        val channel = channels.channel(event.channel)?.takeIf { it.enabled } ?: return
        chat.broadcast(channel, component)
        if (event.discord && source != null) discord().forward(source, plain.serialize(component), preformatted = true)
        if (!event.crossServer) return
        remotePublisher?.invoke(
            BrokerMessage(
                id = UUID.randomUUID().toString(),
                server = config().network.serverId,
                channel = channel.id,
                sender = source?.name,
                component = codec.serialize(component),
                type = BrokerService.TYPE_SYSTEM,
            )
        )
    }

    private fun previousServer(uuid: UUID): String? {
        if (!config().systemMessages.handles(SystemMessagesConfig.Kind.SERVER_SWITCH)) return null
        val window = switchWindow()
        if (window <= 0L) return null
        val from = presence.lastServer(uuid, window) ?: return null
        return from.takeIf { it != config().network.serverId }
    }

    private fun switchWindow(): Long =
        config().systemMessages.events[SystemMessagesConfig.Kind.QUIT]?.delayMillis?.takeIf { it > 0L }
            ?: DEFAULT_SWITCH_WINDOW

    private companion object {
        const val DEFAULT_SWITCH_WINDOW = 5_000L
    }
}
