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
import java.util.concurrent.ConcurrentHashMap

class FormatService(
    private val hooks: HookManager,
    private val config: () -> VoxenConfig,
    private val nickname: (Player) -> Component? = { null },
) {

    private val mm = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacyAmpersand()
    private val customPlaceholders = ConcurrentHashMap<String, FormatPlaceholder>()

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
            val group = hooks.meta.group(player).lowercase()
            channel.groupFormats[group]?.let { return it }
            for ((key, format) in channel.groupFormats) {
                if (player.hasPermission("voxen.chat.format.$key")) return format
            }
        }
        return channel.format
    }

    fun render(format: String, player: Player, channel: Channel, message: Component): Component {
        val expanded = hooks.applyPlaceholders(player, format)
        return mm.deserialize(expanded, resolvers(player, channel, message))
    }

    fun renderConsole(channel: Channel, player: Player, message: Component): Component =
        render(channel.consoleFormat ?: formatFor(channel, player), player, channel, message)

    fun renderExternal(channel: Channel, player: Player, message: Component): Component =
        render(channel.externalFormat ?: formatFor(channel, player), player, channel, message)

    fun metaComponent(raw: String): Component {
        if (raw.isBlank()) return Component.empty()
        return if (raw.contains('<')) {
            runCatching { mm.deserialize(raw) }.getOrElse { legacy.deserialize(raw.replace('§', '&')) }
        } else {
            legacy.deserialize(raw.replace('§', '&'))
        }
    }

    private fun resolvers(player: Player, channel: Channel, message: Component): TagResolver {
        val list = mutableListOf<TagResolver>(
            Placeholder.component("message", message),
            Placeholder.component("player", nickname(player) ?: Component.text(player.name)),
            Placeholder.unparsed("username", player.name),
            Placeholder.component("display_name", player.displayName()),
            Placeholder.unparsed("world", player.world.name),
            Placeholder.parsed("channel", channel.displayName),
            Placeholder.unparsed("channel_id", channel.id),
            Placeholder.unparsed("server", config().serverName),
            Placeholder.component("prefix", metaComponent(hooks.meta.prefix(player))),
            Placeholder.component("suffix", metaComponent(hooks.meta.suffix(player))),
            Placeholder.unparsed("group", hooks.meta.group(player)),
        )
        for ((name, placeholder) in customPlaceholders) {
            val resolved = runCatching { placeholder.resolve(player) }.getOrNull() ?: Component.empty()
            list += Placeholder.component(name, resolved)
        }
        hooks.miniPlaceholders?.let { list += it.resolvers(player) }
        return TagResolver.resolver(list)
    }
}
