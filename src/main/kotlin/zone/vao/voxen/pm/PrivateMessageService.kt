package zone.vao.voxen.pm

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Server
import org.bukkit.entity.Player
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.ignore.IgnoreService
import zone.vao.voxen.storage.PlayerDataService
import zone.vao.voxen.tags.ContentRenderer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PrivateMessageService(
    private val server: Server,
    private val config: () -> VoxenConfig,
    private val playerData: PlayerDataService,
    private val ignores: IgnoreService,
    private val renderer: ContentRenderer,
) {

    private val mm = MiniMessage.miniMessage()
    private val lastConversation = ConcurrentHashMap<UUID, UUID>()

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

        val message = renderer.render(content, sender::hasPermission)
        val resolvers = arrayOf(
            Placeholder.component("message", message),
            Placeholder.unparsed("player", sender.name),
            Placeholder.unparsed("target", target.name),
        )
        sender.sendMessage(mm.deserialize(settings.senderFormat, *resolvers))
        target.sendMessage(mm.deserialize(settings.receiverFormat, *resolvers))
        settings.sound.sound?.let { target.playSound(it) }

        lastConversation[sender.uniqueId] = target.uniqueId
        lastConversation[target.uniqueId] = sender.uniqueId

        val spies = server.onlinePlayers.filter { spy ->
            spy.uniqueId != sender.uniqueId && spy.uniqueId != target.uniqueId &&
                spy.hasPermission(SPY_PERMISSION) && playerData.get(spy.uniqueId).socialSpy
        }
        if (spies.isNotEmpty()) {
            val spyMessage = mm.deserialize(settings.spyFormat, *resolvers)
            for (spy in spies) spy.sendMessage(spyMessage)
            if (settings.notifyMonitored) {
                messages.send(sender, "pm-monitored")
                messages.send(target, "pm-monitored")
            }
        }
        return true
    }

    fun reply(sender: Player, content: String): Boolean {
        val messages = config().messages
        val lastId = lastConversation[sender.uniqueId] ?: run {
            messages.send(sender, "pm-no-reply")
            return false
        }
        val target = server.getPlayer(lastId) ?: run {
            messages.send(sender, "pm-target-offline")
            return false
        }
        return send(sender, target, content)
    }

    fun forget(uuid: UUID) {
        lastConversation.remove(uuid)
    }

    companion object {
        const val SPY_PERMISSION = "voxen.socialspy"
        const val BYPASS_TOGGLE = "voxen.bypass.pmtoggle"
        const val BYPASS_IGNORE = "voxen.bypass.ignore"
    }
}
