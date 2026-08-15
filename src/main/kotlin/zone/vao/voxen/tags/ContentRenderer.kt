package zone.vao.voxen.tags

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.Context
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import zone.vao.voxen.config.TagsConfig

class ContentRenderer(
    private val tagsConfig: () -> TagsConfig,
) {

    fun render(
        content: String,
        hasPermission: (String) -> Boolean,
        extraResolvers: List<TagResolver> = emptyList(),
        modeOverride: TagsConfig.UnauthorizedMode? = null,
    ): Component {
        val config = tagsConfig()
        val mode = modeOverride ?: config.mode
        var raw = content
        if (config.legacyEnabled) {
            raw = LegacyColors.translate(
                raw,
                hasPermission(LEGACY_COLOR) || hasPermission(LEGACY_WILDCARD),
                hasPermission(LEGACY_HEX) || hasPermission(LEGACY_WILDCARD),
                hasPermission(LEGACY_FORMAT) || hasPermission(LEGACY_WILDCARD),
            )
        }
        raw = escapeBlockedParams(raw, config)
        raw = filterCustomTags(raw, config, mode, hasPermission)

        val allowColor = permitted(config, "color", hasPermission)
        val allowHex = permitted(config, "hex", hasPermission)
        val allowed = mutableListOf<TagResolver>()
        val denied = mutableListOf<TagResolver>()
        if (allowColor || allowHex) allowed += ColorTag(allowColor, allowHex, keyword = true)
        if (!allowColor && !allowHex) {
            denied += ColorTag(named = true, hex = true, keyword = true)
        }
        for ((name, rule) in config.rules) {
            if (name == "color" || name == "hex") continue
            val resolver = standardResolver(name, rule, hasPermission) ?: continue
            if (permitted(config, name, hasPermission) || argsPermitted(rule, raw, hasPermission)) {
                allowed += resolver
            } else {
                denied += resolver
            }
        }

        if (mode == TagsConfig.UnauthorizedMode.STRIP && denied.isNotEmpty()) {
            val stripper = MiniMessage.builder().tags(TagResolver.resolver(denied)).build()
            raw = stripper.stripTags(raw)
        }
        val mm = MiniMessage.builder().tags(TagResolver.resolver(allowed + extraResolvers)).build()
        return mm.deserialize(raw)
    }

    fun plain(content: String): String =
        MiniMessage.miniMessage().stripTags(content)

    private fun permitted(config: TagsConfig, name: String, hasPermission: (String) -> Boolean): Boolean {
        val rule = config.rules[name] ?: return false
        if (!rule.enabled) return false
        return hasPermission(rule.permission) || hasPermission(TAG_WILDCARD)
    }

    private fun argsPermitted(rule: TagsConfig.TagRule, raw: String, hasPermission: (String) -> Boolean): Boolean {
        if (!rule.enabled) return false
        val names = rule.names().joinToString("|") { Regex.escape(it) }
        val pattern = Regex("(?<!\\\\)<(?:$names):([^>]*)>", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(raw).toList()
        if (matches.isEmpty()) return false
        return matches.all { match ->
            val node = match.groupValues[1].trim().lowercase().replace(':', '.')
            node.isNotEmpty() && hasPermission("${rule.permission}.$node")
        }
    }

    private fun standardResolver(
        name: String,
        rule: TagsConfig.TagRule,
        hasPermission: (String) -> Boolean,
    ): TagResolver? = when (name) {
        "gradient" -> StandardTags.gradient()
        "rainbow" -> StandardTags.rainbow()
        "transition" -> StandardTags.transition()
        "pride" -> StandardTags.pride()
        "keybind" -> StandardTags.keybind()
        "bold" -> StandardTags.decorations(TextDecoration.BOLD)
        "italic" -> StandardTags.decorations(TextDecoration.ITALIC)
        "underlined" -> StandardTags.decorations(TextDecoration.UNDERLINED)
        "strikethrough" -> StandardTags.decorations(TextDecoration.STRIKETHROUGH)
        "obfuscated" -> StandardTags.decorations(TextDecoration.OBFUSCATED)
        "hover" -> StandardTags.hoverEvent()
        "click" -> ClickTag(rule) { perm -> hasPermission(perm) || hasPermission(TAG_WILDCARD) }
        "insertion" -> StandardTags.insertion()
        "shadow" -> StandardTags.shadowColor()
        "reset" -> StandardTags.reset()
        "newline" -> StandardTags.newline()
        "font" -> StandardTags.font()
        "translatable" -> StandardTags.translatable()
        "fallback" -> StandardTags.translatableFallback()
        "selector" -> StandardTags.selector()
        "score" -> StandardTags.score()
        "nbt" -> StandardTags.nbt()
        "sprite" -> SPRITE
        "head" -> HEAD
        else -> null
    }

    private fun filterCustomTags(
        raw: String,
        config: TagsConfig,
        mode: TagsConfig.UnauthorizedMode,
        hasPermission: (String) -> Boolean,
    ): String {
        var result = raw
        for (rule in config.custom.values) {
            val names = rule.names().joinToString("|") { Regex.escape(it) }
            val pattern = Regex("(?<!\\\\)</?(?:$names)(?::([^>]*))?>", RegexOption.IGNORE_CASE)
            result = pattern.replace(result) { match ->
                when {
                    customPermitted(rule, match.groupValues[1], hasPermission) -> match.value
                    mode == TagsConfig.UnauthorizedMode.STRIP -> ""
                    else -> "\\" + match.value
                }
            }
        }
        return result
    }

    private fun customPermitted(rule: TagsConfig.TagRule, args: String, hasPermission: (String) -> Boolean): Boolean {
        if (!rule.enabled) return false
        if (hasPermission(rule.permission) || hasPermission(TAG_WILDCARD)) return true
        val first = args.substringBefore(':').trim().lowercase()
        return first.isNotEmpty() && hasPermission("${rule.permission}.$first")
    }

    private fun escapeBlockedParams(raw: String, config: TagsConfig): String {
        var result = raw
        for (rule in config.rules.values + config.custom.values) {
            if (rule.blockedParams.isEmpty()) continue
            val names = rule.names().joinToString("|") { Regex.escape(it) }
            val pattern = Regex("<(?:$names):([^>]*)>", RegexOption.IGNORE_CASE)
            result = pattern.replace(result) { match ->
                val params = match.groupValues[1]
                if (rule.blockedParams.any { it.matcher(params).find() }) {
                    "\\" + match.value
                } else {
                    match.value
                }
            }
        }
        return result
    }

    private class ColorTag(
        private val named: Boolean,
        private val hex: Boolean,
        private val keyword: Boolean,
    ) : TagResolver {

        override fun has(name: String): Boolean = when {
            name == "color" || name == "colour" || name == "c" -> keyword
            name.startsWith("#") -> hex && TextColor.fromHexString(name) != null
            else -> named && NamedTextColor.NAMES.value(name.lowercase()) != null
        }

        override fun resolve(name: String, arguments: ArgumentQueue, ctx: Context): Tag {
            val value = if (name == "color" || name == "colour" || name == "c") {
                arguments.popOr("A color value is required").value()
            } else {
                name
            }
            val color = if (value.startsWith("#")) {
                if (!hex) throw ctx.newException("Hex colors are not permitted here.")
                TextColor.fromHexString(value) ?: throw ctx.newException("Invalid hex color '$value'.")
            } else {
                if (!named) throw ctx.newException("Named colors are not permitted here.")
                NamedTextColor.NAMES.value(value.lowercase()) ?: throw ctx.newException("Unknown color '$value'.")
            }
            return Tag.styling { it.color(color) }
        }
    }

    private class ClickTag(
        private val rule: TagsConfig.TagRule,
        private val hasPermission: (String) -> Boolean,
    ) : TagResolver {

        override fun has(name: String): Boolean = name == "click"

        override fun resolve(name: String, arguments: ArgumentQueue, ctx: Context): Tag {
            val action = arguments.popOr("A click action is required").value().lowercase()
            val value = arguments.popOr("A click value is required").value()
            rule.actionPermissions[action]?.let { permission ->
                if (!hasPermission(permission)) {
                    throw ctx.newException("The click action '$action' is not permitted here.")
                }
            }
            val event = when (action) {
                "open_url" -> ClickEvent.openUrl(value)
                "run_command" -> ClickEvent.runCommand(value)
                "suggest_command" -> ClickEvent.suggestCommand(value)
                "copy_to_clipboard" -> ClickEvent.copyToClipboard(value)
                "change_page" -> ClickEvent.changePage(
                    value.toIntOrNull() ?: throw ctx.newException("Invalid page '$value'.")
                )
                else -> throw ctx.newException("The click action '$action' is not supported here.")
            }
            return Tag.styling { it.clickEvent(event) }
        }
    }

    companion object {
        const val TAG_WILDCARD = "voxen.chat.tag.*"

        private val SPRITE: TagResolver? by lazy { optionalStandardTag("sprite") }
        private val HEAD: TagResolver? by lazy { optionalStandardTag("sequentialHead") }

        private fun optionalStandardTag(method: String): TagResolver? =
            runCatching { StandardTags::class.java.getMethod(method).invoke(null) as TagResolver }.getOrNull()
        const val LEGACY_WILDCARD = "voxen.chat.legacy.*"
        const val LEGACY_COLOR = "voxen.chat.legacy.color"
        const val LEGACY_HEX = "voxen.chat.legacy.hex"
        const val LEGACY_FORMAT = "voxen.chat.legacy.format"
    }
}
