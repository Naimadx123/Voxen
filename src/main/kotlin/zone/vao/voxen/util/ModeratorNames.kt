package zone.vao.voxen.util

import zone.vao.voxen.ModeratorResolver
import java.util.concurrent.ConcurrentHashMap

object ModeratorNames {

    private val resolvers = ConcurrentHashMap<String, ModeratorResolver>()

    fun register(prefix: String, resolver: ModeratorResolver): Boolean {
        val lower = prefix.trim().lowercase()
        if (!lower.matches(PREFIX)) return false
        resolvers[lower] = resolver
        return true
    }

    fun unregister(prefix: String) {
        resolvers.remove(prefix.trim().lowercase())
    }

    fun clear() {
        resolvers.clear()
    }

    fun display(name: String?): String? {
        if (name == null || resolvers.isEmpty()) return name
        val separator = name.indexOf(':')
        if (separator <= 0 || separator == name.length - 1) return name
        val resolver = resolvers[name.substring(0, separator).lowercase()] ?: return name
        val resolved = runCatching { resolver.resolve(name.substring(separator + 1)) }.getOrNull() ?: return name
        return resolved.filter { it.code >= 32 }.trim().take(MAX_LENGTH).ifEmpty { name }
    }

    private const val MAX_LENGTH = 64

    private val PREFIX = Regex("[a-z0-9_-]+")
}
