package zone.vao.voxen.command

import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
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

    fun nicknames(plugin: Voxen, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val input = builder.remaining.lowercase()
        plugin.server.onlinePlayers
            .mapNotNull { plugin.playerDataService.get(it.uniqueId).nickname }
            .map { plugin.contentRenderer.plain(it) }
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
