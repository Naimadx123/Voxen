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
object MessageCommand {

    fun buildMessage(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .requires { it.sender.hasPermission("voxen.pm.send") }
            .then(
                Commands.argument("player", StringArgumentType.word())
                    .suggests { ctx, builder -> CommandSuggestions.networkPlayers(plugin, builder, ctx.source.sender) }
                    .then(
                        Commands.argument("message", StringArgumentType.greedyString())
                            .executes { ctx ->
                                val sender = ctx.source.sender as? Player ?: run {
                                    plugin.messages().send(ctx.source.sender, "players-only")
                                    return@executes Command.SINGLE_SUCCESS
                                }
                                val targetName = StringArgumentType.getString(ctx, "player")
                                val content = StringArgumentType.getString(ctx, "message")
                                val target = plugin.server.getPlayerExact(targetName)
                                when {
                                    target != null -> plugin.privateMessageService.send(sender, target, content)
                                    plugin.brokerService.active() -> plugin.privateMessageService.sendRemote(sender, targetName, content)
                                    else -> plugin.messages().send(sender, "player-not-found", Placeholder.unparsed("player", targetName))
                                }
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .build()

    fun buildReply(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .requires { it.sender.hasPermission("voxen.pm.send") }
            .then(
                Commands.argument("message", StringArgumentType.greedyString())
                    .executes { ctx ->
                        val sender = ctx.source.sender as? Player ?: run {
                            plugin.messages().send(ctx.source.sender, "players-only")
                            return@executes Command.SINGLE_SUCCESS
                        }
                        plugin.privateMessageService.reply(sender, StringArgumentType.getString(ctx, "message"))
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
}
