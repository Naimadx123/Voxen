package zone.vao.voxen.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import zone.vao.voxen.Voxen
import zone.vao.voxen.config.HelpopConfig
import zone.vao.voxen.event.HelpRequestEvent
import zone.vao.voxen.util.Durations
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Suppress("UnstableApiUsage")
object HelpopCommand {

    const val PERMISSION = "voxen.helpop"
    const val RECEIVE = "voxen.helpop.receive"
    const val BYPASS_COOLDOWN = "voxen.bypass.cooldown"

    private val lastUse = ConcurrentHashMap<UUID, Long>()

    fun build(plugin: Voxen, name: String): LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name)
            .requires { it.sender.hasPermission(PERMISSION) }
            .executes { ctx ->
                val player = ctx.source.sender as? Player ?: run {
                    plugin.messages().send(ctx.source.sender, "players-only")
                    return@executes Command.SINGLE_SUCCESS
                }
                plugin.ticketService.openPanel(player)
                Command.SINGLE_SUCCESS
            }
            .then(
                Commands.argument("message", StringArgumentType.greedyString())
                    .executes { ctx ->
                        val player = ctx.source.sender as? Player ?: run {
                            plugin.messages().send(ctx.source.sender, "players-only")
                            return@executes Command.SINGLE_SUCCESS
                        }
                        send(plugin, player, StringArgumentType.getString(ctx, "message").trim())
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()

    private fun send(plugin: Voxen, player: Player, content: String) {
        val messages = plugin.messages()
        val settings = plugin.configManager.config.helpop
        if (content.isEmpty()) return
        if (!settings.enabled) {
            messages.send(player, "helpop-disabled")
            return
        }
        val now = System.currentTimeMillis()
        val last = lastUse[player.uniqueId]
        if (last != null && now - last < settings.cooldownMillis && !player.hasPermission(BYPASS_COOLDOWN)) {
            messages.send(
                player,
                "helpop-cooldown",
                Placeholder.unparsed("remaining", Durations.humanize(last + settings.cooldownMillis - now)),
            )
            return
        }
        val tickets = settings.mode == HelpopConfig.Mode.TICKETS
        val event = HelpRequestEvent(player, content.take(settings.maxLength), tickets)
        plugin.server.pluginManager.callEvent(event)
        if (event.isCancelled) return
        val text = event.message.trim().ifEmpty { return }
        lastUse[player.uniqueId] = now
        if (tickets) {
            plugin.ticketService.open(player, text)
            return
        }
        val resolvers = arrayOf(
            Placeholder.unparsed("player", player.name),
            Placeholder.unparsed("message", text),
        )
        messages.send(player, "helpop-sent")
        var staffOnline = false
        for (staff in plugin.server.onlinePlayers) {
            if (staff.uniqueId == player.uniqueId || !staff.hasPermission(RECEIVE)) continue
            staffOnline = true
            plugin.threads.forPlayer(staff) { messages.send(staff, "helpop-alert", *resolvers) }
        }
        messages.send(plugin.server.consoleSender, "helpop-alert", *resolvers)
        if (!staffOnline) messages.send(player, "helpop-nobody")
    }
}
