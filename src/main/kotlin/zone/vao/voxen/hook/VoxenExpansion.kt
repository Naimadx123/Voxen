package zone.vao.voxen.hook

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player
import zone.vao.voxen.Voxen

class VoxenExpansion(private val plugin: Voxen) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "voxen"

    override fun getAuthor(): String = "Naimad"

    override fun getVersion(): String = plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        if (player == null) return null
        return when (params.lowercase()) {
            "channel" -> plugin.channelService.activeChannel(player)?.id ?: ""
            "channel_display" -> plugin.channelService.activeChannel(player)?.displayName ?: ""
            "muted" -> plugin.muteService.isMuted(player.uniqueId, null).toString()
            "party" -> plugin.partyService.partyOf(player.uniqueId)?.name ?: ""
            "party_leader" -> plugin.partyService.partyOf(player.uniqueId)
                ?.let { plugin.server.getOfflinePlayer(it.leader).name } ?: ""
            "language" -> plugin.playerDataService.get(player.uniqueId).language ?: "auto"
            "mentions" -> plugin.playerDataService.get(player.uniqueId).mentionsEnabled.toString()
            "pm" -> plugin.playerDataService.get(player.uniqueId).pmEnabled.toString()
            "chat" -> plugin.playerDataService.get(player.uniqueId).chatEnabled.toString()
            else -> null
        }
    }
}
