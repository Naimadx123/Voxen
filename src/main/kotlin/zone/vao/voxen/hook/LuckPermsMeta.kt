package zone.vao.voxen.hook

import net.luckperms.api.LuckPermsProvider
import org.bukkit.entity.Player

class LuckPermsMeta : MetaProvider {

    override fun prefix(player: Player): String = metaOf(player) { it.prefix }

    override fun suffix(player: Player): String = metaOf(player) { it.suffix }

    override fun group(player: Player): String {
        val user = LuckPermsProvider.get().userManager.getUser(player.uniqueId) ?: return "default"
        return user.primaryGroup
    }

    private fun metaOf(player: Player, extract: (net.luckperms.api.cacheddata.CachedMetaData) -> String?): String {
        val user = LuckPermsProvider.get().userManager.getUser(player.uniqueId) ?: return ""
        return extract(user.cachedData.metaData).orEmpty()
    }
}
