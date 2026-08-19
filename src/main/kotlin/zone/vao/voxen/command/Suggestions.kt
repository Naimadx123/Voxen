package zone.vao.voxen.command

import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import zone.vao.voxen.Voxen
import java.util.concurrent.CompletableFuture

object CommandSuggestions {

    fun onlinePlayers(plugin: Voxen, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val input = builder.remaining.lowercase()
        plugin.server.onlinePlayers
            .filter { it.name.lowercase().startsWith(input) }
            .forEach { builder.suggest(it.name) }
        return builder.buildFuture()
    }

    fun networkPlayers(
        plugin: Voxen,
        builder: SuggestionsBuilder,
        viewer: CommandSender? = null,
    ): CompletableFuture<Suggestions> {
        val input = builder.remaining.lowercase()
        val watcher = viewer as? Player
        val local = plugin.server.onlinePlayers
            .filter { watcher == null || watcher.canSee(it) }
            .map { it.name }
        val remote = if (plugin.configManager.config.presence.suggestRemotePlayers) plugin.presenceService.names() else emptyList()
        (local + remote)
            .filter { it.lowercase().startsWith(input) }
            .distinct()
            .forEach(builder::suggest)
        return builder.buildFuture()
    }

    fun nicknames(plugin: Voxen, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val input = builder.remaining.lowercase()
        plugin.server.onlinePlayers
            .mapNotNull { plugin.playerDataService.get(it.uniqueId).plainNickname(plugin.contentRenderer::plain) }
            .filter { it.isNotEmpty() && it.lowercase().startsWith(input) }
            .forEach(builder::suggest)
        return builder.buildFuture()
    }

    fun channels(plugin: Voxen, builder: SuggestionsBuilder, extra: List<String> = emptyList()): CompletableFuture<Suggestions> {
        val input = builder.remaining.lowercase()
        (plugin.channelService.channels().keys + extra)
            .filter { it.startsWith(input) }
            .forEach(builder::suggest)
        return builder.buildFuture()
    }

    fun languages(plugin: Voxen, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val input = builder.remaining.lowercase()
        (plugin.configManager.config.messages.languages() + "auto")
            .filter { it.lowercase().startsWith(input) }
            .forEach(builder::suggest)
        return builder.buildFuture()
    }
}
