package zone.vao.voxen.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import zone.vao.voxen.Voxen

@Suppress("UnstableApiUsage")
object ToggleCommands {

    fun buildChatToggle(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .executes { ctx ->
                val player = ctx.source.sender as? Player ?: run {
                    plugin.messages().send(ctx.source.sender, "players-only")
                    return@executes Command.SINGLE_SUCCESS
                }
                val data = plugin.playerDataService.get(player.uniqueId)
                data.chatEnabled = !data.chatEnabled
                plugin.playerDataService.save(data)
                plugin.messages().send(player, if (data.chatEnabled) "chattoggle-on" else "chattoggle-off")
                Command.SINGLE_SUCCESS
            }
            .build()

    fun buildFilterToggle(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .requires { it.sender.hasPermission("voxen.filter.toggle") }
            .executes { ctx ->
                val player = ctx.source.sender as? Player ?: run {
                    plugin.messages().send(ctx.source.sender, "players-only")
                    return@executes Command.SINGLE_SUCCESS
                }
                val data = plugin.playerDataService.get(player.uniqueId)
                data.filterEnabled = !data.filterEnabled
                plugin.playerDataService.save(data)
                plugin.messages().send(player, if (data.filterEnabled) "filter-on" else "filter-off")
                Command.SINGLE_SUCCESS
            }
            .build()

    fun buildLanguage(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .executes { ctx ->
                val player = ctx.source.sender as? Player ?: run {
                    plugin.messages().send(ctx.source.sender, "players-only")
                    return@executes Command.SINGLE_SUCCESS
                }
                val current = plugin.playerDataService.get(player.uniqueId).language ?: "auto"
                plugin.messages().send(player, "language-current", Placeholder.unparsed("language", current))
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("language", StringArgumentType.word())
                    .suggests { _, builder -> CommandSuggestions.languages(plugin, builder) }
                    .executes { ctx ->
                        val player = ctx.source.sender as? Player ?: run {
                            plugin.messages().send(ctx.source.sender, "players-only")
                            return@executes Command.SINGLE_SUCCESS
                        }
                        val messages = plugin.messages()
                        val input = StringArgumentType.getString(ctx, "language")
                        val data = plugin.playerDataService.get(player.uniqueId)
                        if (input.equals("auto", ignoreCase = true)) {
                            data.language = null
                            plugin.playerDataService.save(data)
                            messages.send(player, "language-auto")
                            return@executes Command.SINGLE_SUCCESS
                        }
                        if (!messages.hasLanguage(input)) {
                            messages.send(player, "language-unknown", Placeholder.unparsed("language", input))
                            return@executes Command.SINGLE_SUCCESS
                        }
                        data.language = input
                        plugin.playerDataService.save(data)
                        messages.send(player, "language-set", Placeholder.unparsed("language", input))
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
}
