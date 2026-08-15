package zone.vao.voxen.config

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player

class Messages(
    private val defaultLanguage: String,
    private val locales: Map<String, LocaleBundle>,
    private val languageOverride: (Player) -> String?,
) {
    private val mm = MiniMessage.miniMessage()

    data class LocaleBundle(
        val prefix: String,
        val raw: Map<String, String>,
    )

    fun languages(): Set<String> = locales.keys

    fun hasLanguage(language: String): Boolean =
        locales.keys.any { it.equals(language, ignoreCase = true) }

    fun component(audience: Audience, key: String, vararg resolvers: TagResolver): Component {
        val bundle = bundleFor(audience)
        return mm.deserialize(bundle.prefix + template(bundle, key), *resolvers)
    }

    fun line(audience: Audience, key: String, vararg resolvers: TagResolver): Component {
        val bundle = bundleFor(audience)
        return mm.deserialize(template(bundle, key), *resolvers)
    }

    fun send(audience: Audience, key: String, vararg resolvers: TagResolver) {
        audience.sendMessage(component(audience, key, *resolvers))
    }

    fun raw(audience: Audience, key: String): String = template(bundleFor(audience), key)

    private fun template(bundle: LocaleBundle, key: String): String =
        bundle.raw[key] ?: fallback().raw[key] ?: english()?.raw?.get(key) ?: key

    private fun bundleFor(audience: Audience): LocaleBundle {
        if (audience !is Player) return fallback()
        val chosen = languageOverride(audience) ?: audience.locale().toString()
        return lookup(chosen) ?: fallback()
    }

    private fun lookup(language: String): LocaleBundle? {
        locales[language]?.let { return it }
        val lower = language.lowercase()
        locales.entries.firstOrNull { it.key.lowercase() == lower }?.let { return it.value }
        val base = lower.substringBefore('_')
        return locales.entries.firstOrNull { it.key.lowercase().substringBefore('_') == base }?.value
    }

    private fun fallback(): LocaleBundle =
        lookup(defaultLanguage) ?: english() ?: LocaleBundle("", emptyMap())

    private fun english(): LocaleBundle? = lookup("en_US")
}
