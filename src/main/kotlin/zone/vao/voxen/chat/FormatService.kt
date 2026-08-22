package zone.vao.voxen.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import zone.vao.voxen.FormatPlaceholder
import zone.vao.voxen.channel.Channel
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.hook.HookManager
import zone.vao.voxen.util.Components
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FormatService(
    private val hooks: HookManager,
    private val config: () -> VoxenConfig,
    private val nickname: (Player) -> Component? = { null },
) {

    private val mm = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacyAmpersand()
    private val customPlaceholders = ConcurrentHashMap<String, FormatPlaceholder>()

    private val metaCache = ConcurrentHashMap<UUID, Meta>()

    private class Meta(val prefix: String, val suffix: String, val group: String, val at: Long)

    private fun meta(player: Player): Meta {
        val now = System.currentTimeMillis()
        val known = metaCache[player.uniqueId]
        if (known != null && now - known.at < META_TTL_MILLIS) return known
        if (metaCache.size > 512) metaCache.values.removeIf { now - it.at >= META_TTL_MILLIS }
        val fresh = Meta(hooks.meta.prefix(player), hooks.meta.suffix(player), hooks.meta.group(player), now)
        metaCache[player.uniqueId] = fresh
        return fresh
    }

    fun registerPlaceholder(name: String, placeholder: FormatPlaceholder): Boolean {
        val lower = name.lowercase()
        if (!lower.matches(Regex("[a-z0-9_]+"))) return false
        customPlaceholders[lower] = placeholder
        return true
    }

    fun unregisterPlaceholder(name: String) {
        customPlaceholders.remove(name.lowercase())
    }

    fun formatFor(channel: Channel, player: Player): String {
        channel.worldFormats[player.world.name.lowercase()]?.let { return it }
        if (channel.groupFormats.isNotEmpty()) {
            val group = meta(player).group.lowercase()
            channel.groupFormats[group]?.let { return it }
            for ((key, format) in channel.groupFormats) {
                if (player.hasPermission("voxen.chat.format.$key")) return format
            }
        }
        return channel.format
    }

    fun render(format: String, player: Player, channel: Channel, message: Component): Component {
        val meta = meta(player)
        val trimmed = Components.stripEmptyPlaceholders(format) { token ->
            when (token) {
                "<prefix>" -> if (meta.prefix.isBlank()) "" else token
                "<suffix>" -> if (meta.suffix.isBlank()) "" else token
                else -> hooks.applyPlaceholders(player, token)
            }
        }
        val expanded = hooks.applyPlaceholders(player, trimmed)
        return Components.tidy(mm.deserialize(expanded, resolvers(player, channel, message, meta)))
    }

    fun renderConsole(channel: Channel, player: Player, message: Component): Component =
        render(channel.consoleFormat ?: formatFor(channel, player), player, channel, message)

    fun renderExternal(channel: Channel, senderName: String, senderServer: String, message: String): Component {
        val format = channel.externalFormat ?: return Component.text(message)
        val trimmed = Components.stripEmptyPlaceholders(format) { token ->
            if (token == "<prefix>" || token == "<suffix>") "" else token
        }
        return Components.tidy(
            mm.deserialize(
                trimmed,
                Placeholder.unparsed("message", message),
                Placeholder.unparsed("player", senderName),
                Placeholder.unparsed("username", senderName),
                Placeholder.parsed("channel", channel.displayName),
                Placeholder.unparsed("channel_id", channel.id),
                Placeholder.unparsed("server", senderServer),
            )
        )
    }

    fun metaComponent(raw: String): Component {
        if (raw.isBlank()) return Component.empty()
        return if (raw.contains('<')) {
            runCatching { mm.deserialize(raw) }.getOrElse { legacy.deserialize(raw.replace('§', '&')) }
        } else {
            legacy.deserialize(raw.replace('§', '&'))
        }
    }

    private fun resolvers(player: Player, channel: Channel, message: Component, meta: Meta): TagResolver {
        val list = mutableListOf<TagResolver>(
            Placeholder.component("message", message),
            Placeholder.component("player", nickname(player) ?: Component.text(player.name)),
            Placeholder.unparsed("username", player.name),
            Placeholder.component("display_name", player.displayName()),
            Placeholder.unparsed("world", player.world.name),
            Placeholder.parsed("channel", channel.displayName),
            Placeholder.unparsed("channel_id", channel.id),
            Placeholder.unparsed("server", config().serverName),
            Placeholder.component("prefix", metaComponent(meta.prefix)),
            Placeholder.component("suffix", metaComponent(meta.suffix)),
            Placeholder.unparsed("group", meta.group),
        )
        for ((name, placeholder) in customPlaceholders) {
            val resolved = runCatching { placeholder.resolve(player) }.getOrNull() ?: Component.empty()
            list += Placeholder.component(name, resolved)
        }
        hooks.miniPlaceholders?.let { list += it.resolvers(player) }
        return TagResolver.resolver(list)
    }

    private companion object {
        const val META_TTL_MILLIS = 1000L
    }
}
