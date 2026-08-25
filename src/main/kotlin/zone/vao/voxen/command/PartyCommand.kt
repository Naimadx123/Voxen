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
import zone.vao.voxen.party.PartyService

@Suppress("UnstableApiUsage")
object PartyCommand {

    fun build(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .requires { it.sender.hasPermission("voxen.party") }
            .executes { ctx -> list(plugin, ctx) }
            .then(Commands.literal("list").executes { ctx -> list(plugin, ctx) })
            .then(
                Commands.literal("create").then(
                    Commands.argument("name", StringArgumentType.word())
                        .executes { ctx ->
                            val player = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                            val partyName = StringArgumentType.getString(ctx, "name")
                            respond(plugin, player, plugin.partyService.create(player, partyName)) {
                                plugin.messages().send(player, "party-created", Placeholder.unparsed("party", partyName))
                            }
                            Command.SINGLE_SUCCESS
                        }
                )
            )
            .then(
                Commands.literal("disband").executes { ctx ->
                    val player = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                    val party = plugin.partyService.partyOf(player.uniqueId)
                    respond(plugin, player, plugin.partyService.disband(player)) {
                        for (member in party?.members.orEmpty()) {
                            plugin.server.getPlayer(member)?.let {
                                plugin.messages().send(it, "party-disbanded", Placeholder.unparsed("party", party?.name ?: ""))
                            }
                        }
                    }
                    Command.SINGLE_SUCCESS
                }
            )
            .then(
                Commands.literal("invite").then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests { ctx, builder -> CommandSuggestions.onlinePlayers(plugin, builder, ctx.source.sender) }
                        .executes { ctx ->
                            val player = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                            val target = target(plugin, ctx, player) ?: return@executes Command.SINGLE_SUCCESS
                            respond(plugin, player, plugin.partyService.invite(player, target)) {
                                val party = plugin.partyService.partyOf(player.uniqueId)
                                plugin.messages().send(player, "party-invited", Placeholder.unparsed("player", target.name))
                                plugin.messages().send(
                                    target,
                                    "party-invite-received",
                                    Placeholder.unparsed("player", player.name),
                                    Placeholder.unparsed("party", party?.name ?: ""),
                                )
                            }
                            Command.SINGLE_SUCCESS
                        }
                )
            )
            .then(
                Commands.literal("accept").executes { ctx ->
                    val player = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                    respond(plugin, player, plugin.partyService.accept(player)) {
                        val party = plugin.partyService.partyOf(player.uniqueId)
                        plugin.messages().send(player, "party-joined", Placeholder.unparsed("party", party?.name ?: ""))
                        for (member in party?.members.orEmpty()) {
                            if (member == player.uniqueId) continue
                            plugin.server.getPlayer(member)?.let {
                                plugin.messages().send(it, "party-member-joined", Placeholder.unparsed("player", player.name))
                            }
                        }
                    }
                    Command.SINGLE_SUCCESS
                }
            )
            .then(
                Commands.literal("deny").executes { ctx ->
                    val player = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                    respond(plugin, player, plugin.partyService.deny(player)) {
                        plugin.messages().send(player, "party-denied")
                    }
                    Command.SINGLE_SUCCESS
                }
            )
            .then(
                Commands.literal("leave").executes { ctx ->
                    val player = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                    val party = plugin.partyService.partyOf(player.uniqueId)
                    respond(plugin, player, plugin.partyService.leave(player)) {
                        plugin.messages().send(player, "party-left", Placeholder.unparsed("party", party?.name ?: ""))
                        for (member in party?.members.orEmpty()) {
                            if (member == player.uniqueId) continue
                            plugin.server.getPlayer(member)?.let {
                                plugin.messages().send(it, "party-member-left", Placeholder.unparsed("player", player.name))
                            }
                        }
                    }
                    Command.SINGLE_SUCCESS
                }
            )
            .then(
                Commands.literal("kick").then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests { ctx, builder -> CommandSuggestions.onlinePlayers(plugin, builder, ctx.source.sender) }
                        .executes { ctx ->
                            val player = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                            val target = target(plugin, ctx, player) ?: return@executes Command.SINGLE_SUCCESS
                            respond(plugin, player, plugin.partyService.kick(player, target.uniqueId)) {
                                plugin.messages().send(player, "party-kicked", Placeholder.unparsed("player", target.name))
                                plugin.messages().send(target, "party-kicked-target")
                            }
                            Command.SINGLE_SUCCESS
                        }
                )
            )
            .then(
                Commands.literal("transfer").then(
                    Commands.argument("player", StringArgumentType.word())
                        .suggests { ctx, builder -> CommandSuggestions.onlinePlayers(plugin, builder, ctx.source.sender) }
                        .executes { ctx ->
                            val player = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                            val target = target(plugin, ctx, player) ?: return@executes Command.SINGLE_SUCCESS
                            respond(plugin, player, plugin.partyService.transfer(player, target.uniqueId)) {
                                plugin.messages().send(player, "party-transferred", Placeholder.unparsed("player", target.name))
                            }
                            Command.SINGLE_SUCCESS
                        }
                )
            )
            .then(
                Commands.literal("chat").then(
                    Commands.argument("message", StringArgumentType.greedyString())
                        .executes { ctx ->
                            val player = player(plugin, ctx) ?: return@executes Command.SINGLE_SUCCESS
                            if (plugin.partyService.partyOf(player.uniqueId) == null) {
                                plugin.messages().send(player, "party-not-in")
                                return@executes Command.SINGLE_SUCCESS
                            }
                            val channel = plugin.channelService.channels().values
                                .firstOrNull { it.enabled && it.type == zone.vao.voxen.channel.ChannelType.PARTY }
                            if (channel == null) {
                                plugin.messages().send(player, "party-channel-missing")
                                return@executes Command.SINGLE_SUCCESS
                            }
                            plugin.chatService.send(player, channel, StringArgumentType.getString(ctx, "message"))
                            Command.SINGLE_SUCCESS
                        }
                )
            )
            .build()

    private fun list(plugin: Voxen, ctx: CommandContext<CommandSourceStack>): Int {
        val player = player(plugin, ctx) ?: return Command.SINGLE_SUCCESS
        val messages = plugin.messages()
        val party = plugin.partyService.partyOf(player.uniqueId) ?: run {
            messages.send(player, "party-not-in")
            return Command.SINGLE_SUCCESS
        }
        messages.send(
            player,
            "party-list-header",
            Placeholder.unparsed("party", party.name),
            Placeholder.unparsed("amount", party.members.size.toString()),
        )
        for (member in party.members) {
            val memberName = plugin.server.getOfflinePlayer(member).name ?: member.toString()
            player.sendMessage(
                messages.line(
                    player,
                    "party-list-entry",
                    Placeholder.unparsed("player", memberName),
                    Placeholder.unparsed("role", messages.raw(player, if (member == party.leader) "party-role-leader" else "party-role-member")),
                )
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun respond(plugin: Voxen, player: Player, outcome: PartyService.Outcome, onOk: () -> Unit) {
        val messages = plugin.messages()
        when (outcome) {
            PartyService.Outcome.Ok -> onOk()
            PartyService.Outcome.Disabled -> messages.send(player, "party-disabled")
            PartyService.Outcome.AlreadyInParty -> messages.send(player, "party-already-in")
            PartyService.Outcome.NotInParty -> messages.send(player, "party-not-in")
            PartyService.Outcome.NotLeader -> messages.send(player, "party-not-leader")
            PartyService.Outcome.PartyFull -> messages.send(player, "party-full")
            PartyService.Outcome.NoInvite -> messages.send(player, "party-no-invite")
            PartyService.Outcome.TargetInParty -> messages.send(player, "party-target-in-party")
            PartyService.Outcome.InvalidName -> messages.send(player, "party-invalid-name")
        }
    }

    private fun player(plugin: Voxen, ctx: CommandContext<CommandSourceStack>): Player? {
        val sender = ctx.source.sender
        if (sender !is Player) {
            plugin.messages().send(sender, "players-only")
            return null
        }
        return sender
    }

    private fun target(plugin: Voxen, ctx: CommandContext<CommandSourceStack>, sender: Player): Player? {
        val name = StringArgumentType.getString(ctx, "player")
        val target = plugin.server.getPlayerExact(name)
        if (target == null) {
            plugin.messages().send(sender, "player-not-found", Placeholder.unparsed("player", name))
            return null
        }
        return target
    }
}
