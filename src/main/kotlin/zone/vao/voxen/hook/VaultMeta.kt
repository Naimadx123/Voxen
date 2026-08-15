package zone.vao.voxen.hook

import net.milkbowl.vault.chat.Chat
import org.bukkit.Server
import org.bukkit.entity.Player

class VaultMeta(server: Server) : MetaProvider {

    private val chat: Chat? = server.servicesManager.getRegistration(Chat::class.java)?.provider

    fun available(): Boolean = chat != null

    override fun prefix(player: Player): String =
        runCatching { chat?.getPlayerPrefix(player).orEmpty() }.getOrDefault("")

    override fun suffix(player: Player): String =
        runCatching { chat?.getPlayerSuffix(player).orEmpty() }.getOrDefault("")

    override fun group(player: Player): String =
        runCatching { chat?.getPrimaryGroup(player)?.ifEmpty { null } ?: "default" }.getOrDefault("default")
}
