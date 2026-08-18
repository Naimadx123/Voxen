package zone.vao.voxen.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import zone.vao.voxen.Voxen
import zone.vao.voxen.mail.MailService

@Suppress("UnstableApiUsage")
object MailCommand {

    fun build(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .requires { it.sender.hasPermission(MailService.PERMISSION) }
            .then(
                Commands.literal("send")
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.onlinePlayers(plugin, builder) }
                            .then(
                                Commands.argument("message", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val sender = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                                        val targetName = StringArgumentType.getString(ctx, "player")
                                        val target = VoxenCommand.resolve(plugin, targetName) ?: run {
                                            plugin.messages().send(
                                                sender,
                                                "player-not-found",
                                                Placeholder.unparsed("player", targetName),
                                            )
                                            return@executes Command.SINGLE_SUCCESS
                                        }
                                        plugin.mailService.send(
                                            sender,
                                            target.first,
                                            target.second,
                                            StringArgumentType.getString(ctx, "message"),
                                        )
                                        Command.SINGLE_SUCCESS
                                    }
                            )
                    )
            )
            .then(
                Commands.literal("read").executes { ctx ->
                    val sender = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                    plugin.mailService.show(sender, markRead = true)
                    Command.SINGLE_SUCCESS
                }
            )
            .then(
                Commands.literal("list").executes { ctx ->
                    val sender = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                    plugin.mailService.show(sender, markRead = false)
                    Command.SINGLE_SUCCESS
                }
            )
            .then(
                Commands.literal("delete")
                    .then(
                        Commands.argument("index", IntegerArgumentType.integer(1))
                            .executes { ctx ->
                                val sender = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                                plugin.mailService.delete(sender, IntegerArgumentType.getInteger(ctx, "index"))
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("clear").executes { ctx ->
                    val sender = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                    plugin.mailService.clear(sender)
                    Command.SINGLE_SUCCESS
                }
            )
            .executes { ctx ->
                val sender = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                plugin.mailService.show(sender, markRead = true)
                Command.SINGLE_SUCCESS
            }
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
