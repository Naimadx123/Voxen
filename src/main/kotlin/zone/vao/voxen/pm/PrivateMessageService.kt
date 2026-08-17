package zone.vao.voxen.pm

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Server
import org.bukkit.entity.Player
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.ignore.IgnoreService
import zone.vao.voxen.moderation.MuteService
import zone.vao.voxen.network.BrokerMessage
import zone.vao.voxen.network.BrokerService
import zone.vao.voxen.storage.PlayerDataService
import zone.vao.voxen.tags.ContentRenderer
import zone.vao.voxen.util.Durations
import zone.vao.voxen.util.Threads
import java.util.UUID

class PrivateMessageService(
    private val server: Server,
    private val config: () -> VoxenConfig,
    private val playerData: PlayerDataService,
    private val ignores: IgnoreService,
    private val mutes: MuteService,
    private val renderer: ContentRenderer,
    private val threads: Threads,
) {

    @Volatile
    var remotePublisher: ((BrokerMessage) -> Unit)? = null

    @Volatile
    var scheduleTimeout: ((Long, () -> Unit) -> Unit)? = null

    private val mm = MiniMessage.miniMessage()
    private val pending = PendingMessages()

    private fun setConversation(playerUuid: UUID, otherUuid: UUID, otherName: String) {
        val data = playerData.get(playerUuid)
        data.lastPmUuid = otherUuid.toString()
        data.lastPmName = otherName
        playerData.save(data)
    }

    fun send(sender: Player, target: Player, content: String): Boolean {
        val messages = config().messages
        val settings = config().privateMessages
        if (!settings.enabled) {
            messages.send(sender, "pm-disabled")
            return false
        }
        if (target.uniqueId == sender.uniqueId) {
            messages.send(sender, "pm-self")
            return false
        }
        if (isMuted(sender)) return false
        val targetName = Placeholder.unparsed("target", target.name)
        if (!playerData.get(target.uniqueId).pmEnabled && !sender.hasPermission(BYPASS_TOGGLE)) {
            messages.send(sender, "pm-target-disabled", targetName)
            return false
        }
        if (ignores.isIgnoring(target.uniqueId, sender.uniqueId) && !sender.hasPermission(BYPASS_IGNORE)) {
            messages.send(sender, "pm-ignored", targetName)
            return false
        }
        if (ignores.isIgnoring(sender.uniqueId, target.uniqueId)) {
            messages.send(sender, "pm-you-ignore", targetName)
            return false
        }

        val message = renderer.render(content, sender::hasPermission, isPermissionSet = sender::isPermissionSet)
        val resolvers = arrayOf<TagResolver>(
            Placeholder.component("message", message),
            Placeholder.unparsed("player", sender.name),
            Placeholder.unparsed("target", target.name),
        )
        sender.sendMessage(mm.deserialize(settings.senderFormat, *resolvers))
        val received = mm.deserialize(settings.receiverFormat, *resolvers)
        val sound = settings.sound.sound
        threads.forPlayer(target) {
            target.sendMessage(received)
            sound?.let { target.playSound(it) }
        }

        setConversation(sender.uniqueId, target.uniqueId, target.name)
        setConversation(target.uniqueId, sender.uniqueId, sender.name)

        notifySpies(sender.uniqueId, target.uniqueId, resolvers) {
            if (config().privateMessages.notifyMonitored) {
                messages.send(sender, "pm-monitored")
                messages.send(target, "pm-monitored")
            }
        }
        broadcastSpy(sender.name, sender.uniqueId, target.name, target.uniqueId, message)
        return true
    }

    fun sendRemote(sender: Player, targetName: String, content: String): Boolean {
        val messages = config().messages
        val settings = config().privateMessages
        val publisher = remotePublisher ?: run {
            messages.send(sender, "player-not-found", Placeholder.unparsed("player", targetName))
            return false
        }
        if (!settings.enabled) {
            messages.send(sender, "pm-disabled")
            return false
        }
        if (targetName.equals(sender.name, ignoreCase = true)) {
            messages.send(sender, "pm-self")
            return false
        }
        if (isMuted(sender)) return false
        val message = renderer.render(content, sender::hasPermission, isPermissionSet = sender::isPermissionSet)
        val requestId = UUID.randomUUID().toString()
        pending.add(requestId, sender.uniqueId, targetName, message)
        val flags = buildList {
            if (sender.hasPermission(BYPASS_TOGGLE)) add("pmtoggle")
            if (sender.hasPermission(BYPASS_IGNORE)) add("ignore")
        }
        publisher(
            BrokerMessage(
                id = requestId,
                server = null,
                channel = null,
                sender = sender.name,
                component = null,
                mm = mm.serialize(message),
                type = BrokerService.TYPE_PM,
                target = targetName,
                senderUuid = sender.uniqueId.toString(),
                flags = flags.joinToString(","),
            )
        )
        scheduleTimeout?.invoke(config().network.timeoutMillis) {
            val timedOut = pending.drop(requestId) ?: return@invoke
            server.getPlayer(timedOut.senderUuid)?.let {
                config().messages.send(it, "player-not-found", Placeholder.unparsed("player", timedOut.targetName))
            }
        }
        return true
    }

    fun handleRemote(message: BrokerMessage) {
        when (message.type) {
            BrokerService.TYPE_PM -> handleRequest(message)
            BrokerService.TYPE_PM_ACK -> handleAck(message)
            BrokerService.TYPE_PM_SPY -> handleSpy(message)
        }
    }

    fun reply(sender: Player, content: String): Boolean {
        val messages = config().messages
        val data = playerData.get(sender.uniqueId)
        val lastUuid = runCatching { UUID.fromString(data.lastPmUuid) }.getOrNull()
        val lastName = data.lastPmName
        if (lastUuid == null || lastName == null) {
            messages.send(sender, "pm-no-reply")
            return false
        }
        val target = server.getPlayer(lastUuid)
        if (target != null) return send(sender, target, content)
        if (remotePublisher != null) return sendRemote(sender, lastName, content)
        messages.send(sender, "pm-target-offline")
        return false
    }

    fun forget(uuid: UUID) {
        pending.forget(uuid)
    }

    private fun isMuted(sender: Player): Boolean {
        if (!config().privateMessages.respectMutes) return false
        val mute = mutes.activeMute(sender.uniqueId, null) ?: return false
        val messages = config().messages
        val remaining = mute.expiresAt?.let { Durations.humanize(it - System.currentTimeMillis()) }
            ?: messages.raw(sender, "mute-permanent")
        messages.send(
            sender,
            "you-are-muted",
            Placeholder.unparsed("reason", mute.reason ?: messages.raw(sender, "mute-no-reason")),
            Placeholder.unparsed("remaining", remaining),
        )
        return true
    }

    private fun handleRequest(request: BrokerMessage) {
        val target = server.getPlayerExact(request.target ?: return) ?: return
        val senderName = request.sender ?: return
        val senderUuid = runCatching { UUID.fromString(request.senderUuid) }.getOrNull() ?: return
        val settings = config().privateMessages
        if (!settings.enabled) {
            ack(request, target, "pm-disabled")
            return
        }
        val flags = request.flags.orEmpty().split(',')
        if (!playerData.get(target.uniqueId).pmEnabled && "pmtoggle" !in flags) {
            ack(request, target, "pm-target-disabled")
            return
        }
        if (ignores.isIgnoring(target.uniqueId, senderUuid) && "ignore" !in flags) {
            ack(request, target, "pm-ignored")
            return
        }
        val message = runCatching { mm.deserialize(request.mm ?: return) }.getOrNull() ?: return
        val resolvers = arrayOf<TagResolver>(
            Placeholder.component("message", message),
            Placeholder.unparsed("player", senderName),
            Placeholder.unparsed("target", target.name),
        )
        val received = mm.deserialize(settings.receiverFormat, *resolvers)
        val sound = settings.sound.sound
        threads.forPlayer(target) {
            target.sendMessage(received)
            sound?.let { target.playSound(it) }
        }
        setConversation(target.uniqueId, senderUuid, senderName)
        notifySpies(senderUuid, target.uniqueId, resolvers) {
            if (config().privateMessages.notifyMonitored) config().messages.send(target, "pm-monitored")
        }
        broadcastSpy(senderName, senderUuid, target.name, target.uniqueId, message)
        ack(request, target, "ok")
    }

    private fun handleAck(ackMessage: BrokerMessage) {
        val senderUuid = runCatching { UUID.fromString(ackMessage.senderUuid) }.getOrNull()
        val entry = pending.claim(ackMessage.replyTo, senderUuid, ackMessage.target) ?: return
        val targetName = ackMessage.target ?: entry.targetName
        val sender = server.getPlayer(entry.senderUuid) ?: return
        val messages = config().messages
        val settings = config().privateMessages
        if (ackMessage.status != "ok") {
            messages.send(
                sender,
                ackMessage.status ?: "player-not-found",
                Placeholder.unparsed("target", targetName),
                Placeholder.unparsed("player", targetName),
            )
            return
        }
        val resolvers = arrayOf<TagResolver>(
            Placeholder.component("message", entry.message),
            Placeholder.unparsed("player", sender.name),
            Placeholder.unparsed("target", targetName),
        )
        sender.sendMessage(mm.deserialize(settings.senderFormat, *resolvers))
        runCatching { UUID.fromString(ackMessage.targetUuid) }.getOrNull()?.let {
            setConversation(sender.uniqueId, it, targetName)
        }
    }

    private fun handleSpy(spyMessage: BrokerMessage) {
        val settings = config().privateMessages
        if (!settings.enabled) return
        val message = runCatching { mm.deserialize(spyMessage.mm ?: return) }.getOrNull() ?: return
        val resolvers = arrayOf<TagResolver>(
            Placeholder.component("message", message),
            Placeholder.unparsed("player", spyMessage.sender ?: return),
            Placeholder.unparsed("target", spyMessage.target ?: return),
        )
        val senderUuid = runCatching { UUID.fromString(spyMessage.senderUuid) }.getOrNull()
        val targetUuid = runCatching { UUID.fromString(spyMessage.targetUuid) }.getOrNull()
        notifySpies(senderUuid, targetUuid, resolvers) {}
    }

    private fun broadcastSpy(senderName: String, senderUuid: UUID, targetName: String, targetUuid: UUID, message: Component) {
        remotePublisher?.invoke(
            BrokerMessage(
                id = UUID.randomUUID().toString(),
                server = null,
                channel = null,
                sender = senderName,
                component = null,
                mm = mm.serialize(message),
                type = BrokerService.TYPE_PM_SPY,
                target = targetName,
                senderUuid = senderUuid.toString(),
                targetUuid = targetUuid.toString(),
            )
        )
    }

    private fun ack(request: BrokerMessage, target: Player, status: String) {
        remotePublisher?.invoke(
            BrokerMessage(
                id = UUID.randomUUID().toString(),
                server = null,
                channel = null,
                sender = request.sender,
                component = null,
                type = BrokerService.TYPE_PM_ACK,
                target = target.name,
                senderUuid = request.senderUuid,
                targetUuid = target.uniqueId.toString(),
                status = status,
                replyTo = request.id,
            )
        )
    }

    private fun notifySpies(senderId: UUID?, targetId: UUID?, resolvers: Array<TagResolver>, onSpied: () -> Unit) {
        val settings = config().privateMessages
        val spies = server.onlinePlayers.filter { spy ->
            spy.uniqueId != senderId && spy.uniqueId != targetId &&
                spy.hasPermission(SPY_PERMISSION) && playerData.get(spy.uniqueId).socialSpy
        }
        if (spies.isEmpty()) return
        val spyMessage = mm.deserialize(settings.spyFormat, *resolvers)
        for (spy in spies) threads.forPlayer(spy) { spy.sendMessage(spyMessage) }
        onSpied()
    }

    companion object {
        const val SPY_PERMISSION = "voxen.socialspy"
        const val BYPASS_TOGGLE = "voxen.bypass.pmtoggle"
        const val BYPASS_IGNORE = "voxen.bypass.ignore"
    }
}
