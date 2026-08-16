package zone.vao.voxen.hook

import io.github.miniplaceholders.api.Expansion
import io.github.miniplaceholders.api.utils.TagsUtils
import net.kyori.adventure.text.minimessage.tag.Tag
import org.bukkit.entity.Player
import zone.vao.voxen.Voxen
import zone.vao.voxen.tags.Replacements

object VoxenTags {

    fun register(plugin: Voxen) {
        val builder = Expansion.builder("voxen").filter(Player::class.java)
        for ((name, value) in placeholders(plugin)) {
            builder.audiencePlaceholder(name) { audience, _, _ ->
                val player = audience as? Player ?: return@audiencePlaceholder TagsUtils.EMPTY_TAG
                TagsUtils.staticTag(value(player))
            }
        }
        builder.audiencePlaceholder("tag") { audience, queue, _ ->
            val player = audience as? Player ?: return@audiencePlaceholder TagsUtils.EMPTY_TAG
            if (!queue.hasNext()) return@audiencePlaceholder TagsUtils.EMPTY_TAG
            val component = Replacements.component(
                plugin.hookManager,
                plugin.configManager.config.tags,
                player,
                queue.pop().value(),
            ) ?: return@audiencePlaceholder TagsUtils.EMPTY_TAG
            Tag.selfClosingInserting(component)
        }
        builder.build().register()
    }

    private fun placeholders(plugin: Voxen): Map<String, (Player) -> String> = mapOf(
        "channel" to { player -> plugin.channelService.activeChannel(player)?.id ?: "" },
        "channel_display" to { player -> plugin.channelService.activeChannel(player)?.displayName ?: "" },
        "muted" to { player -> plugin.muteService.isMuted(player.uniqueId, null).toString() },
        "party" to { player -> plugin.partyService.partyOf(player.uniqueId)?.name ?: "" },
        "party_leader" to { player ->
            plugin.partyService.partyOf(player.uniqueId)
                ?.let { plugin.server.getOfflinePlayer(it.leader).name } ?: ""
        },
        "language" to { player -> plugin.playerDataService.get(player.uniqueId).language ?: "auto" },
        "mentions" to { player -> plugin.playerDataService.get(player.uniqueId).mentionsEnabled.toString() },
        "pm" to { player -> plugin.playerDataService.get(player.uniqueId).pmEnabled.toString() },
        "chat" to { player -> plugin.playerDataService.get(player.uniqueId).chatEnabled.toString() },
        "nickname" to { player ->
            plugin.playerDataService.get(player.uniqueId)
                .plainNickname(plugin.contentRenderer::plain) ?: player.name
        },
        "server" to { _ -> plugin.configManager.config.serverName },
        "similarity_threshold" to { _ -> plugin.configManager.config.moderation.similarityThresholdPercent },
    )
}
