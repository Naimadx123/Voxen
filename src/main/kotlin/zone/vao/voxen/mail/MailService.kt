package zone.vao.voxen.mail

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Server
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.entity.Player
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.pm.PrivateMessageService
import zone.vao.voxen.storage.MailEntry
import zone.vao.voxen.storage.PlayerDataService
import zone.vao.voxen.util.Durations
import zone.vao.voxen.util.Threads
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MailService(
    private val server: Server,
    private val config: () -> VoxenConfig,
    private val playerData: PlayerDataService,
    private val pm: PrivateMessageService,
    private val threads: Threads,
    private val onlineElsewhere: (String) -> Boolean,
) : Listener {

    private val lastSent = ConcurrentHashMap<UUID, Long>()

    /** Resolves [targetName] locally, falling back to a storage lookup for players who never joined here. */
    fun send(sender: Player, targetName: String, content: String) {
        val local = runCatching { UUID.fromString(targetName) }.getOrNull()
            ?.let { id -> id to (server.getOfflinePlayer(id).name ?: id.toString()) }
            ?: server.getPlayerExact(targetName)?.let { it.uniqueId to it.name }
        if (local != null) {
            send(sender, local.first, local.second, content)
            return
        }
        playerData.async { storage ->
            val found = storage.findByName(targetName)
            threads.forPlayer(sender) {
                if (!sender.isOnline) return@forPlayer
                if (found == null) {
                    config().messages.send(sender, "player-not-found", Placeholder.unparsed("player", targetName))
                } else {
                    send(sender, found.first, found.second, content)
                }
            }
        }
    }

    fun send(sender: Player, targetUuid: UUID, targetName: String, content: String) {
        val settings = config().mail
        val messages = config().messages
        val target = Placeholder.unparsed("player", targetName)
        if (!settings.enabled) {
            messages.send(sender, "mail-disabled")
            return
        }
        if (targetUuid == sender.uniqueId) {
            messages.send(sender, "mail-self")
            return
        }
        if (!settings.allowWhenOnline &&
            (server.getPlayer(targetUuid) != null || onlineElsewhere(targetName))
        ) {
            messages.send(sender, "mail-target-online", target)
            return
        }
        val cooldown = settings.cooldownMillis
        val guarded = cooldown > 0 && !sender.hasPermission(BYPASS_COOLDOWN)
        val now = System.currentTimeMillis()
        if (guarded) {
            // claimed before anything async runs, otherwise two quick commands both pass the check
            val previous = lastSent.merge(sender.uniqueId, now) { old, fresh ->
                if (fresh - old >= cooldown) fresh else old
            } ?: now
            if (previous != now) {
                val remaining = cooldown - (now - previous)
                messages.send(sender, "mail-cooldown", Placeholder.unparsed("remaining", Durations.humanize(remaining)))
                return
            }
        }
        fun releaseCooldown() {
            if (guarded) lastSent.remove(sender.uniqueId, now)
        }
        if (pm.isMuted(sender)) {
            releaseCooldown()
            return
        }
        val text = pm.moderate(sender, content) ?: run {
            releaseCooldown()
            return
        }

        val entry = MailEntry(
            id = UUID.randomUUID(),
            recipient = targetUuid,
            senderUuid = sender.uniqueId,
            senderName = sender.name,
            content = text,
            server = config().network.serverId,
            createdAt = System.currentTimeMillis(),
        )
        val bypassIgnore = sender.hasPermission(PrivateMessageService.BYPASS_IGNORE)
        playerData.async { storage ->
            val ignored = !bypassIgnore && storage.loadIgnores(targetUuid).contains(sender.uniqueId)
            val stored = !ignored && storage.saveMailIfRoom(entry, settings.maxPerPlayer)
            if (!stored) releaseCooldown()
            threads.forPlayer(sender) {
                when {
                    ignored -> messages.send(sender, "mail-ignored", target)
                    !stored -> messages.send(sender, "mail-full", target)
                    else -> {
                        messages.send(sender, "mail-sent", target)
                        server.getPlayer(targetUuid)?.let { online ->
                            threads.forPlayer(online) {
                                config().messages.send(
                                    online,
                                    "mail-notify",
                                    Placeholder.unparsed("amount", "1"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun show(player: Player, markRead: Boolean) {
        val messages = config().messages
        if (!config().mail.enabled) {
            messages.send(player, "mail-disabled")
            return
        }
        playerData.async { storage ->
            val entries = storage.mailFor(player.uniqueId, unreadOnly = false)
            if (markRead && entries.any { it.readAt == null }) storage.markMailRead(player.uniqueId)
            threads.forPlayer(player) {
                if (entries.isEmpty()) {
                    messages.send(player, "mail-empty")
                    return@forPlayer
                }
                messages.send(player, "mail-header", Placeholder.unparsed("amount", entries.size.toString()))
                entries.forEachIndexed { index, entry ->
                    player.sendMessage(
                        messages.line(
                            player,
                            if (entry.readAt == null) "mail-entry-unread" else "mail-entry",
                            Placeholder.unparsed("index", (index + 1).toString()),
                            Placeholder.unparsed("time", TIME_FORMAT.format(Instant.ofEpochMilli(entry.createdAt))),
                            Placeholder.unparsed("player", entry.senderName),
                            Placeholder.unparsed("server", entry.server),
                            Placeholder.unparsed("message", entry.content),
                        )
                    )
                }
            }
        }
    }

    fun delete(player: Player, index: Int) {
        val messages = config().messages
        if (!config().mail.enabled) {
            messages.send(player, "mail-disabled")
            return
        }
        playerData.async { storage ->
            val entry = storage.mailFor(player.uniqueId, unreadOnly = false).getOrNull(index - 1)
            val deleted = entry != null && storage.deleteMail(player.uniqueId, entry.id)
            threads.forPlayer(player) {
                messages.send(player, if (deleted) "mail-deleted" else "mail-invalid-index")
            }
        }
    }

    fun clear(player: Player) {
        val messages = config().messages
        if (!config().mail.enabled) {
            messages.send(player, "mail-disabled")
            return
        }
        playerData.async { storage ->
            val removed = storage.clearMail(player.uniqueId)
            threads.forPlayer(player) {
                if (removed == 0) messages.send(player, "mail-empty")
                else messages.send(player, "mail-cleared", Placeholder.unparsed("amount", removed.toString()))
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val settings = config().mail
        if (!settings.enabled || !settings.notifyOnJoin) return
        val player = event.player
        playerData.async { storage ->
            val unread = storage.mailFor(player.uniqueId, unreadOnly = true).size
            if (unread == 0) return@async
            threads.forPlayer(player) {
                if (!player.isOnline) return@forPlayer
                config().messages.send(player, "mail-notify", Placeholder.unparsed("amount", unread.toString()))
            }
        }
    }

    companion object {
        const val PERMISSION = "voxen.mail"
        private const val BYPASS_COOLDOWN = "voxen.bypass.cooldown"
        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
