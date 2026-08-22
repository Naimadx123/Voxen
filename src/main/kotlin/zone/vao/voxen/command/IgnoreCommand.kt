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
object IgnoreCommand {

    fun buildIgnore(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .requires { it.sender.hasPermission("voxen.ignore") }
            .then(
                Commands.argument("player", StringArgumentType.word())
                    .suggests { ctx, builder -> CommandSuggestions.onlinePlayers(plugin, builder, ctx.source.sender) }
                    .executes { ctx ->
                        val sender = ctx.source.sender as? Player ?: run {
                            plugin.messages().send(ctx.source.sender, "players-only")
                            return@executes Command.SINGLE_SUCCESS
                        }
                        val messages = plugin.messages()
                        val targetName = StringArgumentType.getString(ctx, "player")
                        val target = plugin.server.getPlayerExact(targetName)
                            ?: plugin.server.getOfflinePlayerIfCached(targetName)?.takeIf { it.name != null }
                            ?: run {
                                messages.send(sender, "player-not-found", Placeholder.unparsed("player", targetName))
                                return@executes Command.SINGLE_SUCCESS
                            }
                        val resolvedName = Placeholder.unparsed("player", target.name ?: targetName)
                        if (target.uniqueId == sender.uniqueId) {
                            messages.send(sender, "ignore-self")
                            return@executes Command.SINGLE_SUCCESS
                        }
                        if (plugin.ignoreService.isIgnoring(sender.uniqueId, target.uniqueId)) {
                            plugin.ignoreService.unignore(sender.uniqueId, target.uniqueId)
                            messages.send(sender, "ignore-removed", resolvedName)
                        } else {
                            plugin.ignoreService.ignore(sender.uniqueId, target.uniqueId)
                            messages.send(sender, "ignore-added", resolvedName)
                        }
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()

    fun buildIgnoreList(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .requires { it.sender.hasPermission("voxen.ignore") }
            .executes { ctx ->
                val sender = ctx.source.sender as? Player ?: run {
                    plugin.messages().send(ctx.source.sender, "players-only")
                    return@executes Command.SINGLE_SUCCESS
                }
                val messages = plugin.messages()
                val ignored = plugin.ignoreService.ignored(sender.uniqueId)
                if (ignored.isEmpty()) {
                    messages.send(sender, "ignore-list-empty")
                    return@executes Command.SINGLE_SUCCESS
                }
                messages.send(sender, "ignore-list-header", Placeholder.unparsed("amount", ignored.size.toString()))
                for (uuid in ignored) {
                    val ignoredName = plugin.server.getOfflinePlayer(uuid).name ?: uuid.toString()
                    sender.sendMessage(messages.line(sender, "ignore-list-entry", Placeholder.unparsed("player", ignoredName)))
                }
                Command.SINGLE_SUCCESS
            }
            .build()
}
