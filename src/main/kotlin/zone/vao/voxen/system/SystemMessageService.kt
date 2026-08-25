package zone.vao.voxen.system

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.Event
import zone.vao.voxen.NetworkPlayer
import zone.vao.voxen.channel.ChannelService
import zone.vao.voxen.chat.ChatService
import zone.vao.voxen.chat.FormatService
import zone.vao.voxen.config.NetworkConfig
import zone.vao.voxen.config.SystemMessagesConfig
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.event.NetworkJoinEvent
import zone.vao.voxen.event.NetworkQuitEvent
import zone.vao.voxen.event.NetworkSwitchEvent
import zone.vao.voxen.hook.DiscordHooks
import zone.vao.voxen.network.BrokerMessage
import zone.vao.voxen.network.BrokerService
import zone.vao.voxen.presence.PresenceService
import zone.vao.voxen.util.Vanish
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SystemMessageService(
    private val server: Server,
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
        val component: Component?,
        val event: SystemMessagesConfig.Event?,
        val player: Player,
        val name: String,
        val server: String,
    )

    fun handles(kind: SystemMessagesConfig.Kind): Boolean = config().systemMessages.handles(kind)

    fun hidden(player: Player): Boolean =
        config().systemMessages.respectVanish && Vanish.hidden(player)

    fun join(player: Player) {
        val rejoined = leaving.remove(player.uniqueId) != null
        if (hidden(player)) return
        val from = previousServer(player.uniqueId)
        if (from == null && rejoined) return
        arrived(player, from)
        val switched = from != null && config().systemMessages.handles(SystemMessagesConfig.Kind.SERVER_SWITCH)
        val kind = when {
            switched -> SystemMessagesConfig.Kind.SERVER_SWITCH
            !player.hasPlayedBefore() &&
                config().systemMessages.handles(SystemMessagesConfig.Kind.FIRST_JOIN) ->
                SystemMessagesConfig.Kind.FIRST_JOIN
            else -> SystemMessagesConfig.Kind.JOIN
        }
        val extra = if (!switched) emptyArray() else arrayOf<TagResolver>(
            Placeholder.unparsed("from", from.orEmpty()),
            Placeholder.unparsed("to", config().network.serverId),
        )
        announce(kind, player, *extra)
    }

    private fun arrived(player: Player, from: String?) {
        val here = config().network.serverId
        val info = NetworkPlayer(player.uniqueId, player.name, here, System.currentTimeMillis())
        val event: Event = if (from == null) NetworkJoinEvent(info) else NetworkSwitchEvent(info, from)
        server.pluginManager.callEvent(event)
    }

    fun quit(player: Player) {
        if (hidden(player)) return
        val settings = config().systemMessages
        val event = settings.event(SystemMessagesConfig.Kind.QUIT)
        val pending = Pending(
            component = event?.let { render(it, player) },
            event = event,
            player = player,
            name = player.name,
            server = config().network.serverId,
        )
        val delay = if (networked()) settings.events[SystemMessagesConfig.Kind.QUIT]?.delayMillis ?: 0L else 0L
        val uuid = player.uniqueId
        val scheduler = schedule
        if (delay <= 0L || scheduler == null) {
            release(uuid, pending)
            return
        }
        leaving[uuid] = pending
        scheduler(delay) { leaving.remove(uuid)?.let { held -> release(uuid, held) } }
    }

    private fun release(uuid: UUID, pending: Pending) {
        server.pluginManager.callEvent(NetworkQuitEvent(uuid, pending.name, pending.server))
        val event = pending.event ?: return
        val component = pending.component ?: return
        publish(component, event, pending.player)
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

    private fun networked(): Boolean {
        val settings = config()
        return settings.presence.enabled && settings.network.transport != NetworkConfig.Transport.NONE
    }

    private fun previousServer(uuid: UUID): String? {
        if (!networked()) return null
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
