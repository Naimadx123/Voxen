package zone.vao.voxen.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import zone.vao.voxen.Voxen
import zone.vao.voxen.moderation.ModeratorService
import zone.vao.voxen.report.ReportService

@Suppress("UnstableApiUsage")
object ReportCommand {

    fun build(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .requires { it.sender.hasPermission(ReportService.PERMISSION) }
            .then(
                Commands.argument("player", StringArgumentType.word())
                    .suggests { ctx, builder -> CommandSuggestions.networkPlayers(plugin, builder, ctx.source.sender) }
                    .executes { ctx -> report(plugin, ctx, null) }
                    .then(
                        Commands.argument("reason", StringArgumentType.greedyString())
                            .suggests { _, builder ->
                                val input = builder.remaining.lowercase()
                                plugin.configManager.config.reports.reasons
                                    .filter { it.id.startsWith(input) }
                                    .forEach { builder.suggest(it.id) }
                                builder.buildFuture()
                            }
                            .executes { ctx -> report(plugin, ctx, StringArgumentType.getString(ctx, "reason")) }
                    )
            )
            .build()

    private fun report(plugin: Voxen, ctx: CommandContext<CommandSourceStack>, reason: String?): Int {
        val sender = ctx.source.sender
        val player = sender as? Player ?: run {
            plugin.messages().send(sender, "players-only")
            return Command.SINGLE_SUCCESS
        }
        val name = StringArgumentType.getString(ctx, "player")
        val target = VoxenCommand.resolve(plugin, name) ?: run {
            plugin.messages().send(sender, "player-not-found", Placeholder.unparsed("player", name))
            return Command.SINGLE_SUCCESS
        }
        plugin.reportService.submit(player, ModeratorService.Target(target.first, target.second), reason)
        return Command.SINGLE_SUCCESS
    }
}
