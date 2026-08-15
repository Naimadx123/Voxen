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
import zone.vao.voxen.channel.Channel
import zone.vao.voxen.channel.ChannelService

@Suppress("UnstableApiUsage")
object ChannelCommand {

    fun build(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .executes { ctx -> list(plugin, ctx) }
            .then(Commands.literal("list").executes { ctx -> list(plugin, ctx) })
            .then(
                Commands.literal("join").then(
                    channelArgument(plugin).executes { ctx -> join(plugin, ctx) }
                )
            )
            .then(
                Commands.literal("leave").then(
                    channelArgument(plugin).executes { ctx -> leave(plugin, ctx) }
                )
            )
            .then(
                Commands.literal("set").then(
                    channelArgument(plugin).executes { ctx -> set(plugin, ctx) }
                )
            )
            .build()

    fun buildAlias(plugin: Voxen, alias: String, channelId: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(alias)
            .executes { ctx ->
                val player = playerOrMessage(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                val channel = plugin.channelService.channel(channelId) ?: return@executes Command.SINGLE_SUCCESS
                setActive(plugin, player, channel)
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("message", StringArgumentType.greedyString())
                    .executes { ctx ->
                        val player = playerOrMessage(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                        val channel = plugin.channelService.channel(channelId) ?: return@executes Command.SINGLE_SUCCESS
                        plugin.chatService.send(player, channel, StringArgumentType.getString(ctx, "message"))
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()

    private fun channelArgument(plugin: Voxen) =
        Commands.argument("channel", StringArgumentType.word())
            .suggests { _, builder -> CommandSuggestions.channels(plugin, builder) }

    private fun list(plugin: Voxen, ctx: CommandContext<CommandSourceStack>): Int {
        val player = playerOrMessage(plugin, ctx) ?: return Command.SINGLE_SUCCESS
        val messages = plugin.messages()
        val active = plugin.channelService.activeChannel(player)
        messages.send(player, "channel-list-header")
        for (channel in plugin.channelService.channels().values) {
            if (!channel.enabled || !channel.canRead(player)) continue
            val joined = ChannelService.isJoined(channel, plugin.playerDataService.get(player.uniqueId))
            player.sendMessage(
                messages.line(
                    player,
                    "channel-list-entry",
                    Placeholder.parsed("channel", channel.displayName),
                    Placeholder.unparsed("channel_id", channel.id),
                    Placeholder.unparsed("state", messages.raw(player, when {
                        active?.id == channel.id -> "channel-state-active"
                        joined -> "channel-state-joined"
                        else -> "channel-state-left"
                    })),
                )
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun join(plugin: Voxen, ctx: CommandContext<CommandSourceStack>): Int {
        val player = playerOrMessage(plugin, ctx) ?: return Command.SINGLE_SUCCESS
        val channel = findChannel(plugin, ctx, player) ?: return Command.SINGLE_SUCCESS
        if (!plugin.channelService.join(player, channel)) {
            plugin.messages().send(player, "channel-cannot-join", Placeholder.parsed("channel", channel.displayName))
            return Command.SINGLE_SUCCESS
        }
        plugin.messages().send(player, "channel-joined", Placeholder.parsed("channel", channel.displayName))
        return Command.SINGLE_SUCCESS
    }

    private fun leave(plugin: Voxen, ctx: CommandContext<CommandSourceStack>): Int {
        val player = playerOrMessage(plugin, ctx) ?: return Command.SINGLE_SUCCESS
        val channel = findChannel(plugin, ctx, player) ?: return Command.SINGLE_SUCCESS
        if (!plugin.channelService.leave(player, channel)) {
            plugin.messages().send(player, "channel-not-joined", Placeholder.parsed("channel", channel.displayName))
            return Command.SINGLE_SUCCESS
        }
        plugin.messages().send(player, "channel-left", Placeholder.parsed("channel", channel.displayName))
        return Command.SINGLE_SUCCESS
    }

    private fun set(plugin: Voxen, ctx: CommandContext<CommandSourceStack>): Int {
        val player = playerOrMessage(plugin, ctx) ?: return Command.SINGLE_SUCCESS
        val channel = findChannel(plugin, ctx, player) ?: return Command.SINGLE_SUCCESS
        setActive(plugin, player, channel)
        return Command.SINGLE_SUCCESS
    }

    private fun setActive(plugin: Voxen, player: Player, channel: Channel) {
        if (!plugin.channelService.setActive(player, channel)) {
            plugin.messages().send(player, "channel-cannot-join", Placeholder.parsed("channel", channel.displayName))
            return
        }
        plugin.messages().send(player, "channel-set", Placeholder.parsed("channel", channel.displayName))
    }

    private fun findChannel(plugin: Voxen, ctx: CommandContext<CommandSourceStack>, player: Player): Channel? {
        val input = StringArgumentType.getString(ctx, "channel")
        val channel = plugin.channelService.channel(input)
        if (channel == null || !channel.enabled) {
            plugin.messages().send(player, "channel-not-found", Placeholder.unparsed("channel", input))
            return null
        }
        return channel
    }

    private fun playerOrMessage(plugin: Voxen, ctx: CommandContext<CommandSourceStack>): Player? {
        val sender = ctx.source.sender
        if (sender !is Player) {
            plugin.messages().send(sender, "players-only")
            return null
        }
        return sender
    }
}
