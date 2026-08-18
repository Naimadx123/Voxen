package zone.vao.voxen.pm

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Server
import org.bukkit.entity.Player
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.ignore.IgnoreService
import zone.vao.voxen.network.BrokerMessage
import zone.vao.voxen.network.BrokerService
import zone.vao.voxen.presence.PresenceService
import zone.vao.voxen.util.Threads
import java.util.UUID

class GroupService(
    private val server: Server,
    private val config: () -> VoxenConfig,
    private val pm: PrivateMessageService,
    private val ignores: IgnoreService,
    private val presence: PresenceService,
    private val threads: Threads,
) {

    @Volatile
    var remotePublisher: ((BrokerMessage) -> Unit)? = null

    private val mm = MiniMessage.miniMessage()
    private val registry = GroupRegistry({ config().privateMessages.group.idleMillis })

    fun groupOf(uuid: UUID): GroupRegistry.Group? = registry.of(uuid)

    fun create(sender: Player, names: List<String>) {
        val messages = config().messages
        val settings = config().privateMessages.group
        if (!enabled(sender)) return
        if (groupOf(sender.uniqueId) != null) {
            messages.send(sender, "group-already-in")
            return
        }
        val members = LinkedHashMap<UUID, String>()
        members[sender.uniqueId] = sender.name
        for (name in names) {
            if (name.equals(sender.name, ignoreCase = true)) continue
            val member = lookup(name) ?: run {
                messages.send(sender, "player-not-found", Placeholder.unparsed("player", name))
                return
            }
            if (members.size >= settings.maxMembers) {
                messages.send(sender, "group-full", Placeholder.unparsed("max", settings.maxMembers.toString()))
                return
            }
            members[member.first] = member.second
        }
        if (members.size < 2) {
            messages.send(sender, "group-needs-members")
            return
        }
        val group = registry.create(members)
        announce(group, "group-created", sender.name)
    }

    fun invite(sender: Player, name: String) {
        val messages = config().messages
        val settings = config().privateMessages.group
        if (!enabled(sender)) return
        val group = groupOf(sender.uniqueId) ?: run {
            messages.send(sender, "group-none")
            return
        }
        if (group.members.size >= settings.maxMembers) {
            messages.send(sender, "group-full", Placeholder.unparsed("max", settings.maxMembers.toString()))
            return
        }
        val member = lookup(name) ?: run {
            messages.send(sender, "player-not-found", Placeholder.unparsed("player", name))
            return
        }
        if (member.first in group.members) {
            messages.send(sender, "group-already-member", Placeholder.unparsed("player", member.second))
            return
        }
        if (groupOf(member.first) != null) {
            messages.send(sender, "group-target-busy", Placeholder.unparsed("player", member.second))
            return
        }
        group.members[member.first] = member.second
        registry.touch(group)
        announce(group, "group-joined", member.second)
    }

    fun leave(sender: Player) {
        val messages = config().messages
        if (!enabled(sender)) return
        val group = groupOf(sender.uniqueId) ?: run {
            messages.send(sender, "group-none")
            return
        }
        group.members.remove(sender.uniqueId)
        registry.touch(group)
        messages.send(sender, "group-left")
        if (group.members.size < 2) {
            registry.remove(group.id)
            notify(group, "group-closed", null)
            publishState(group, closed = true)
            return
        }
        announce(group, "group-left-other", sender.name)
    }

    fun list(sender: Player) {
        val messages = config().messages
        if (!enabled(sender)) return
        val group = groupOf(sender.uniqueId) ?: run {
            messages.send(sender, "group-none")
            return
        }
        messages.send(sender, "group-list-header", Placeholder.unparsed("amount", group.members.size.toString()))
        for (name in group.members.values) {
            sender.sendMessage(messages.line(sender, "group-list-entry", Placeholder.unparsed("player", name)))
        }
    }

    fun send(sender: Player, content: String) {
        val messages = config().messages
        if (!enabled(sender)) return
        val group = groupOf(sender.uniqueId) ?: run {
            messages.send(sender, "group-none")
            return
        }
        if (!config().privateMessages.enabled) {
            messages.send(sender, "pm-disabled")
            return
        }
        if (pm.isMuted(sender)) return
        val text = pm.moderate(sender, content) ?: return
        val message = pm.renderContent(sender, text)
        registry.touch(group)
        val resolvers = resolvers(sender.name, message)
        deliver(group, sender.uniqueId, resolvers)
        pm.notifySpies(sender.uniqueId, null, resolvers) {}
        pm.logHistory(sender, text)
        publish(group, sender.name, mm.serialize(message))
    }

    fun handleRemote(message: BrokerMessage) {
        val settings = config().privateMessages
        if (!settings.enabled || !settings.group.enabled) return
        val id = runCatching { UUID.fromString(message.target) }.getOrNull() ?: return
        val group = registry.merge(id, parseRoster(message.roster.orEmpty())) ?: return
        val senderName = message.sender ?: return
        val senderUuid = runCatching { UUID.fromString(message.senderUuid) }.getOrNull()
        val raw = message.mm
        if (raw == null) {
            message.status?.let { notify(group, it, senderName) }
            return
        }
        val rendered = runCatching { mm.deserialize(raw) }.getOrNull() ?: return
        val resolvers = resolvers(senderName, rendered)
        threads.main {
            deliver(group, senderUuid, resolvers)
            pm.notifySpies(senderUuid, null, resolvers) {}
        }
    }

    fun clear() {
        registry.clear()
    }

    private fun enabled(sender: Player): Boolean {
        val settings = config().privateMessages
        if (settings.enabled && settings.group.enabled) return true
        config().messages.send(sender, "group-disabled")
        return false
    }

    private fun lookup(name: String): Pair<UUID, String>? {
        server.getPlayerExact(name)?.let { return it.uniqueId to it.name }
        return presence.find(name)?.let { it.uuid to it.name }
    }

    private fun resolvers(senderName: String, message: net.kyori.adventure.text.Component): Array<TagResolver> =
        arrayOf(
            Placeholder.component("message", message),
            Placeholder.unparsed("player", senderName),
            Placeholder.unparsed("target", "group"),
        )

    private fun deliver(group: GroupRegistry.Group, senderUuid: UUID?, resolvers: Array<TagResolver>) {
        val line = mm.deserialize(config().privateMessages.group.format, *resolvers)
        val sound = config().privateMessages.sound.sound
        for (uuid in group.members.keys) {
            val member = server.getPlayer(uuid) ?: continue
            if (uuid != senderUuid && senderUuid != null && ignores.isIgnoring(uuid, senderUuid)) continue
            threads.forPlayer(member) {
                member.sendMessage(line)
                if (uuid != senderUuid) sound?.let { member.playSound(it) }
            }
        }
    }

    private fun announce(group: GroupRegistry.Group, key: String, subject: String?) {
        notify(group, key, subject)
        publishState(group, closed = false, status = key, subject = subject)
    }

    private fun notify(group: GroupRegistry.Group, key: String, subject: String?) {
        val messages = config().messages
        val resolver = Placeholder.unparsed("player", subject ?: "")
        for (uuid in group.members.keys) {
            val member = server.getPlayer(uuid) ?: continue
            threads.forPlayer(member) { messages.send(member, key, resolver) }
        }
    }

    private fun publish(group: GroupRegistry.Group, senderName: String, serialized: String) {
        publisher()?.invoke(base(group).copy(sender = senderName, mm = serialized, senderUuid = senderUuid(senderName)))
    }

    private fun publishState(group: GroupRegistry.Group, closed: Boolean, status: String? = null, subject: String? = null) {
        publisher()?.invoke(
            base(group).copy(
                sender = subject,
                status = status,
                roster = if (closed) emptyList() else group.members.map { "${it.key}:${it.value}" },
            )
        )
    }

    private fun base(group: GroupRegistry.Group): BrokerMessage = BrokerMessage(
        id = UUID.randomUUID().toString(),
        server = config().network.serverId,
        channel = null,
        sender = null,
        component = null,
        type = BrokerService.TYPE_PM_GROUP,
        target = group.id.toString(),
        roster = group.members.map { "${it.key}:${it.value}" },
    )

    private fun senderUuid(name: String): String? = server.getPlayerExact(name)?.uniqueId?.toString()

    private fun publisher(): ((BrokerMessage) -> Unit)? = remotePublisher

    private fun parseRoster(roster: List<String>): LinkedHashMap<UUID, String> {
        val members = LinkedHashMap<UUID, String>()
        for (raw in roster) {
            val uuid = runCatching { UUID.fromString(raw.substringBefore(':')) }.getOrNull() ?: continue
            val name = raw.substringAfter(':', "").trim()
            if (name.isEmpty() || name.length > 16) continue
            members[uuid] = name
        }
        return members
    }
}
