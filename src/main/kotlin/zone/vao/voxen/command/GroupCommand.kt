package zone.vao.voxen.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.entity.Player
import zone.vao.voxen.Voxen

@Suppress("UnstableApiUsage")
object GroupCommand {

    fun build(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .requires { it.sender.hasPermission("voxen.pm.group") }
            .then(
                Commands.literal("create")
                    .then(
                        Commands.argument("players", StringArgumentType.greedyString())
                            .suggests { ctx, builder -> CommandSuggestions.networkPlayers(plugin, builder, ctx.source.sender) }
                            .executes { ctx ->
                                val sender = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                                val names = StringArgumentType.getString(ctx, "players")
                                    .split(' ', ',')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .distinct()
                                plugin.groupService.create(sender, names)
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("invite")
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { ctx, builder -> CommandSuggestions.networkPlayers(plugin, builder, ctx.source.sender) }
                            .executes { ctx ->
                                val sender = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                                plugin.groupService.invite(sender, StringArgumentType.getString(ctx, "player"))
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("leave").executes { ctx ->
                    val sender = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                    plugin.groupService.leave(sender)
                    Command.SINGLE_SUCCESS
                }
            )
            .then(
                Commands.literal("list").executes { ctx ->
                    val sender = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                    plugin.groupService.list(sender)
                    Command.SINGLE_SUCCESS
                }
            )
            .then(
                Commands.argument("message", StringArgumentType.greedyString())
                    .executes { ctx ->
                        val sender = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                        plugin.groupService.send(sender, StringArgumentType.getString(ctx, "message"))
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()

    private fun player(
        plugin: Voxen,
        ctx: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
    ): Player? {
        val sender = ctx.source.sender
        if (sender is Player) return sender
        plugin.messages().send(sender, "players-only")
        return null
    }
}
