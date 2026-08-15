package zone.vao.voxen.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import zone.vao.voxen.Voxen
import zone.vao.voxen.moderation.WordFilter

@Suppress("UnstableApiUsage")
object NickCommand {

    const val PERMISSION = "voxen.nick"
    const val PERMISSION_OTHERS = "voxen.nick.others"
    const val PERMISSION_REALNAME = "voxen.realname"

    fun build(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .requires { it.sender.hasPermission(PERMISSION) }
            .executes { ctx -> show(plugin, ctx.source.sender) }
            .then(
                Commands.argument("nickname", StringArgumentType.word())
                    .executes { ctx -> setOwn(plugin, ctx) }
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .requires { it.sender.hasPermission(PERMISSION_OTHERS) }
                            .suggests { _, builder -> CommandSuggestions.onlinePlayers(plugin, builder) }
                            .executes { ctx -> setOther(plugin, ctx) }
                    )
            )
            .build()

    fun buildRealName(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .requires { it.sender.hasPermission(PERMISSION_REALNAME) }
            .then(
                Commands.argument("nickname", StringArgumentType.greedyString())
                    .suggests { _, builder -> CommandSuggestions.nicknames(plugin, builder) }
                    .executes { ctx -> realName(plugin, ctx.source.sender, StringArgumentType.getString(ctx, "nickname")) }
            )
            .build()

    private fun realName(plugin: Voxen, sender: CommandSender, input: String): Int {
        val messages = plugin.messages()
        if (!plugin.configManager.config.nicknames.enabled) {
            messages.send(sender, "nickname-disabled")
            return Command.SINGLE_SUCCESS
        }
        val query = input.trim()
        val matches = plugin.server.onlinePlayers.filter { plainNickname(plugin, it).equals(query, ignoreCase = true) }
        if (matches.isEmpty()) {
            messages.send(sender, "realname-none", Placeholder.unparsed("nickname", query))
            return Command.SINGLE_SUCCESS
        }
        for (match in matches) {
            messages.send(
                sender,
                "realname-found",
                Placeholder.unparsed("nickname", plainNickname(plugin, match).orEmpty()),
                Placeholder.unparsed("player", match.name),
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun plainNickname(plugin: Voxen, player: Player): String? =
        plugin.playerDataService.get(player.uniqueId).plainNickname(plugin.contentRenderer::plain)

    private fun show(plugin: Voxen, sender: CommandSender): Int {
        val messages = plugin.messages()
        val player = sender as? Player ?: run {
            messages.send(sender, "players-only")
            return Command.SINGLE_SUCCESS
        }
        val nickname = plugin.playerDataService.get(player.uniqueId).nickname
        if (nickname == null) {
            messages.send(player, "nickname-none")
        } else {
            messages.send(player, "nickname-current", nicknamePlaceholder(plugin, player, nickname))
        }
        return Command.SINGLE_SUCCESS
    }

    private fun setOwn(plugin: Voxen, ctx: CommandContext<CommandSourceStack>): Int {
        val messages = plugin.messages()
        val player = ctx.source.sender as? Player ?: run {
            messages.send(ctx.source.sender, "players-only")
            return Command.SINGLE_SUCCESS
        }
        return apply(plugin, player, player, StringArgumentType.getString(ctx, "nickname"))
    }

    private fun setOther(plugin: Voxen, ctx: CommandContext<CommandSourceStack>): Int {
        val messages = plugin.messages()
        val sender = ctx.source.sender
        val targetName = StringArgumentType.getString(ctx, "player")
        val target = plugin.server.getPlayerExact(targetName) ?: run {
            messages.send(sender, "player-not-found", Placeholder.unparsed("player", targetName))
            return Command.SINGLE_SUCCESS
        }
        return apply(plugin, sender, target, StringArgumentType.getString(ctx, "nickname"))
    }

    private fun apply(plugin: Voxen, sender: CommandSender, target: Player, input: String): Int {
        val messages = plugin.messages()
        val config = plugin.configManager.config.nicknames
        if (!config.enabled) {
            messages.send(sender, "nickname-disabled")
            return Command.SINGLE_SUCCESS
        }
        val data = plugin.playerDataService.get(target.uniqueId)
        if (input.equals("reset", true) || input.equals("off", true)) {
            data.nickname = null
            plugin.playerDataService.save(data)
            if (sender !== target) {
                messages.send(sender, "nickname-reset-other", Placeholder.unparsed("player", target.name))
            }
            messages.send(target, "nickname-reset")
            return Command.SINGLE_SUCCESS
        }
        val visible = plugin.contentRenderer.plain(input)
        if (visible.length < config.minLength || visible.length > config.maxLength) {
            messages.send(
                sender,
                "nickname-length",
                Placeholder.unparsed("min", config.minLength.toString()),
                Placeholder.unparsed("max", config.maxLength.toString()),
            )
            return Command.SINGLE_SUCCESS
        }
        if (config.filter && plugin.wordFilter.check(visible) != WordFilter.Result.Clean) {
            messages.send(sender, "nickname-blocked")
            return Command.SINGLE_SUCCESS
        }
        data.nickname = input
        plugin.playerDataService.save(data)
        val rendered = nicknamePlaceholder(plugin, target, input)
        if (sender !== target) {
            messages.send(sender, "nickname-set-other", Placeholder.unparsed("player", target.name), rendered)
        }
        messages.send(target, "nickname-set", rendered)
        return Command.SINGLE_SUCCESS
    }

    private fun nicknamePlaceholder(plugin: Voxen, owner: Player, nickname: String) =
        Placeholder.component("nickname", plugin.contentRenderer.render(nickname, owner::hasPermission, isPermissionSet = owner::isPermissionSet))
}
