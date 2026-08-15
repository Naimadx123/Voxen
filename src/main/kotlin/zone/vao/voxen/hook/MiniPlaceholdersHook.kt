package zone.vao.voxen.hook

import io.github.miniplaceholders.api.MiniPlaceholders
import net.kyori.adventure.text.minimessage.Context
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player

class MiniPlaceholdersHook {

    init {
        MiniPlaceholders.getGlobalPlaceholders()
    }

    fun resolvers(player: Player): TagResolver = MiniPlaceholders.getAudienceGlobalPlaceholders(player)

    fun gated(player: Player, allowed: (String) -> Boolean): TagResolver = Gated(resolvers(player), allowed)

    private class Gated(
        private val delegate: TagResolver,
        private val allowed: (String) -> Boolean,
    ) : TagResolver {

        override fun has(name: String): Boolean = allowed(name) && delegate.has(name)

        override fun resolve(name: String, arguments: ArgumentQueue, ctx: Context): Tag? =
            if (allowed(name)) delegate.resolve(name, arguments, ctx) else null
    }
}
