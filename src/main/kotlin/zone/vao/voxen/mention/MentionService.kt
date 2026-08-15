package zone.vao.voxen.mention

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import zone.vao.voxen.config.MentionsConfig
import zone.vao.voxen.storage.PlayerDataService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

class MentionService(
    private val mentions: () -> MentionsConfig,
    private val playerData: PlayerDataService,
) {

    private val mm = MiniMessage.miniMessage()
    private val lastMention = ConcurrentHashMap<UUID, Long>()

    fun mentionedNames(content: String): Set<String> =
        NAME_PATTERN.findAll(content).mapTo(LinkedHashSet()) { it.groupValues[1].lowercase() }

    fun tryUse(sender: Player): Boolean {
        val config = mentions()
        if (!config.enabled) return false
        if (config.cooldownMillis <= 0 || sender.hasPermission(BYPASS_COOLDOWN)) {
            lastMention[sender.uniqueId] = System.currentTimeMillis()
            return true
        }
        val now = System.currentTimeMillis()
        val last = lastMention[sender.uniqueId] ?: 0L
        if (now - last < config.cooldownMillis) return false
        lastMention[sender.uniqueId] = now
        return true
    }

    fun accepts(recipient: Player): Boolean =
        mentions().enabled && playerData.get(recipient.uniqueId).mentionsEnabled

    fun highlight(message: Component, recipient: Player): Component {
        val config = mentions()
        val replacement = mm.deserialize(config.highlight, Placeholder.unparsed("player", recipient.name))
        return message.replaceText(
            TextReplacementConfig.builder()
                .match(Pattern.compile("@" + Pattern.quote(recipient.name), Pattern.CASE_INSENSITIVE))
                .replacement(replacement)
                .build()
        )
    }

    fun notify(recipient: Player) {
        mentions().sound.sound?.let { recipient.playSound(it) }
    }

    fun forget(uuid: UUID) {
        lastMention.remove(uuid)
    }

    private companion object {
        val NAME_PATTERN = Regex("@([A-Za-z0-9_]{2,16})")
        const val BYPASS_COOLDOWN = "voxen.bypass.mention-cooldown"
    }
}
