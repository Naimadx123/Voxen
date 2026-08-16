package zone.vao.voxen.tags

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import zone.vao.voxen.config.TagsConfig
import zone.vao.voxen.hook.HookManager

object Replacements {

    private val mm = MiniMessage.miniMessage()

    fun find(config: TagsConfig, name: String): TagsConfig.TagRule? =
        config.replacements.values.firstOrNull { rule ->
            rule.names().any { it.equals(name, ignoreCase = true) }
        }

    fun permitted(rule: TagsConfig.TagRule, player: Player): Boolean =
        rule.enabled &&
            rule.value.isNotEmpty() &&
            (
                !rule.requirePermission ||
                    player.hasPermission(rule.permission) ||
                    player.hasPermission(ContentRenderer.TAG_WILDCARD)
                )

    fun render(hooks: HookManager, rule: TagsConfig.TagRule, player: Player): Component =
        mm.deserialize(hooks.applyPlaceholders(player, rule.value))

    fun component(hooks: HookManager, config: TagsConfig, player: Player, name: String): Component? {
        val rule = find(config, name) ?: return null
        if (!permitted(rule, player)) return null
        return render(hooks, rule, player)
    }
}
