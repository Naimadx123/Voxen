package zone.vao.voxen.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import zone.vao.voxen.Voxen
import zone.vao.voxen.moderation.ModeratorService
import zone.vao.voxen.moderation.MuteEntry
import zone.vao.voxen.report.ReportService
import zone.vao.voxen.util.Durations
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Suppress("UnstableApiUsage")
object VoxenCommand {

    fun build(plugin: Voxen): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("voxen")
            .executes { ctx -> sendHelp(plugin, ctx.source.sender) }
            .then(Commands.literal("help").executes { ctx -> sendHelp(plugin, ctx.source.sender) })
            .then(
                permLiteral("reload", "voxen.admin")
                    .executes { ctx ->
                        plugin.reload()
                        plugin.messages().send(ctx.source.sender, "reloaded")
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                permLiteral("status", "voxen.admin")
                    .executes { ctx -> sendStatus(plugin, ctx.source.sender) }
            )
            .then(
                permLiteral("find", "voxen.find")
                    .executes { ctx -> listPresence(plugin, ctx.source.sender) }
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { ctx, builder -> CommandSuggestions.networkPlayers(plugin, builder, ctx.source.sender) }
                            .executes { ctx -> find(plugin, ctx.source.sender, arg(ctx, "player")) }
                    )
            )
            .then(
                permLiteral("mutes", "voxen.mod.mute")
                    .executes { ctx -> listMutes(plugin, ctx.source.sender) }
            )
            .then(
                permLiteral("mute", "voxen.mod.mute")
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.onlinePlayers(plugin, builder) }
                            .executes { ctx -> mute(plugin, ctx, null, null, null) }
                            .then(
                                Commands.argument("duration", StringArgumentType.word())
                                    .executes { ctx -> mute(plugin, ctx, arg(ctx, "duration"), null, null) }
                                    .then(
                                        Commands.argument("channel", StringArgumentType.word())
                                            .suggests { _, builder -> CommandSuggestions.channels(plugin, builder, listOf("all")) }
                                            .executes { ctx -> mute(plugin, ctx, arg(ctx, "duration"), arg(ctx, "channel"), null) }
                                            .then(
                                                Commands.argument("reason", StringArgumentType.greedyString())
                                                    .executes { ctx ->
                                                        mute(plugin, ctx, arg(ctx, "duration"), arg(ctx, "channel"), arg(ctx, "reason"))
                                                    }
                                            )
                                    )
                            )
                    )
            )
            .then(
                permLiteral("muteinfo", "voxen.mod.mute")
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.onlinePlayers(plugin, builder) }
                            .executes { ctx -> muteInfo(plugin, ctx) }
                    )
            )
            .then(
                permLiteral("history", "voxen.mod.history")
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.onlinePlayers(plugin, builder) }
                            .executes { ctx -> history(plugin, ctx) }
                    )
            )
            .then(
                permLiteral("unmute", "voxen.mod.mute")
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.onlinePlayers(plugin, builder) }
                            .executes { ctx -> unmute(plugin, ctx, null) }
                            .then(
                                Commands.argument("channel", StringArgumentType.word())
                                    .suggests { _, builder -> CommandSuggestions.channels(plugin, builder, listOf("all")) }
                                    .executes { ctx -> unmute(plugin, ctx, arg(ctx, "channel")) }
                            )
                    )
            )
            .then(
                permLiteral("mutechat", "voxen.mod.mutechat")
                    .executes { ctx ->
                        val muted = !plugin.muteService.globalChatMuted
                        plugin.muteService.setGlobalChatMuted(muted)
                        val key = if (muted) "chatmute-on" else "chatmute-off"
                        for (player in plugin.server.onlinePlayers) {
                            plugin.messages().send(player, key)
                        }
                        if (ctx.source.sender !is Player) plugin.messages().send(ctx.source.sender, key)
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                permLiteral("mutechannel", "voxen.mod.mutechannel")
                    .then(
                        Commands.argument("channel", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.channels(plugin, builder) }
                            .executes { ctx ->
                                val sender = ctx.source.sender
                                val channel = plugin.channelService.channel(arg(ctx, "channel")) ?: run {
                                    plugin.messages().send(sender, "channel-not-found", Placeholder.unparsed("channel", arg(ctx, "channel")))
                                    return@executes Command.SINGLE_SUCCESS
                                }
                                val muted = !plugin.muteService.isChannelMuted(channel.id)
                                plugin.muteService.setChannelMuted(channel.id, muted)
                                plugin.messages().send(
                                    sender,
                                    if (muted) "channelmute-on" else "channelmute-off",
                                    Placeholder.parsed("channel", channel.displayName),
                                )
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                permLiteral("slowmode", "voxen.mod.slowmode")
                    .then(
                        Commands.argument("channel", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.channels(plugin, builder) }
                            .then(
                                Commands.argument("time", StringArgumentType.word())
                                    .executes { ctx -> slowmode(plugin, ctx) }
                            )
                    )
            )
            .then(
                permLiteral("chatclear", "voxen.mod.chatclear")
                    .executes { ctx -> chatClear(plugin, ctx.source.sender, null) }
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.onlinePlayers(plugin, builder) }
                            .executes { ctx -> chatClear(plugin, ctx.source.sender, arg(ctx, "player")) }
                    )
            )
            .then(
                permLiteral("inspect", "voxen.mod.inspect")
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.onlinePlayers(plugin, builder) }
                            .executes { ctx -> staff(plugin, ctx) { sender, target -> plugin.moderatorService.inspect(sender, target) } }
                    )
            )
            .then(
                permLiteral("warn", "voxen.mod.warn")
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.onlinePlayers(plugin, builder) }
                            .executes { ctx -> staff(plugin, ctx) { sender, target -> plugin.moderatorService.warn(sender, target, null) } }
                            .then(
                                Commands.argument("reason", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        val reason = arg(ctx, "reason")
                                        staff(plugin, ctx) { sender, target -> plugin.moderatorService.warn(sender, target, reason) }
                                    }
                            )
                    )
            )
            .then(
                permLiteral("warns", "voxen.mod.warn")
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.onlinePlayers(plugin, builder) }
                            .executes { ctx -> staff(plugin, ctx) { sender, target -> plugin.moderatorService.warnings(sender, target) } }
                            .then(
                                Commands.argument("page", IntegerArgumentType.integer(1))
                                    .executes { ctx ->
                                        val page = IntegerArgumentType.getInteger(ctx, "page")
                                        staff(plugin, ctx) { sender, target ->
                                            plugin.moderatorService.warnings(sender, target, page)
                                        }
                                    }
                            )
                    )
            )
            .then(
                permLiteral("unwarn", "voxen.mod.warn")
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.onlinePlayers(plugin, builder) }
                            .then(
                                Commands.argument("index", IntegerArgumentType.integer(1))
                                    .executes { ctx ->
                                        val index = IntegerArgumentType.getInteger(ctx, "index")
                                        staff(plugin, ctx) { sender, target -> plugin.moderatorService.unwarn(sender, target, index) }
                                    }
                            )
                    )
            )
            .then(
                permLiteral("notes", "voxen.mod.notes")
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.onlinePlayers(plugin, builder) }
                            .executes { ctx -> staff(plugin, ctx) { sender, target -> plugin.moderatorService.notes(sender, target) } }
                            .then(
                                Commands.argument("page", IntegerArgumentType.integer(1))
                                    .executes { ctx ->
                                        val page = IntegerArgumentType.getInteger(ctx, "page")
                                        staff(plugin, ctx) { sender, target ->
                                            plugin.moderatorService.notes(sender, target, page)
                                        }
                                    }
                            )
                            .then(
                                Commands.literal("add")
                                    .then(
                                        Commands.argument("text", StringArgumentType.greedyString())
                                            .executes { ctx ->
                                                val text = arg(ctx, "text")
                                                staff(plugin, ctx) { sender, target -> plugin.moderatorService.addNote(sender, target, text) }
                                            }
                                    )
                            )
                            .then(
                                Commands.literal("del")
                                    .then(
                                        Commands.argument("index", IntegerArgumentType.integer(1))
                                            .executes { ctx ->
                                                val index = IntegerArgumentType.getInteger(ctx, "index")
                                                staff(plugin, ctx) { sender, target -> plugin.moderatorService.deleteNote(sender, target, index) }
                                            }
                                    )
                            )
                    )
            )
            .then(
                permLiteral("delete", "voxen.mod.delete")
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .suggests { _, builder -> CommandSuggestions.onlinePlayers(plugin, builder) }
                            .executes { ctx -> staff(plugin, ctx) { sender, target -> plugin.moderatorService.deleteMessages(sender, target, 1) } }
                            .then(
                                Commands.argument("amount", IntegerArgumentType.integer(1, 100))
                                    .executes { ctx ->
                                        val amount = IntegerArgumentType.getInteger(ctx, "amount")
                                        staff(plugin, ctx) { sender, target -> plugin.moderatorService.deleteMessages(sender, target, amount) }
                                    }
                            )
                    )
            )
            .then(
                Commands.literal("mentions")
                    .executes { ctx ->
                        val player = ctx.source.sender as? Player ?: run {
                            plugin.messages().send(ctx.source.sender, "players-only")
                            return@executes Command.SINGLE_SUCCESS
                        }
                        val data = plugin.playerDataService.get(player.uniqueId)
                        data.mentionsEnabled = !data.mentionsEnabled
                        plugin.playerDataService.save(data)
                        plugin.messages().send(player, if (data.mentionsEnabled) "mentions-on" else "mentions-off")
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("pm")
                    .executes { ctx ->
                        val player = ctx.source.sender as? Player ?: run {
                            plugin.messages().send(ctx.source.sender, "players-only")
                            return@executes Command.SINGLE_SUCCESS
                        }
                        val data = plugin.playerDataService.get(player.uniqueId)
                        data.pmEnabled = !data.pmEnabled
                        plugin.playerDataService.save(data)
                        plugin.messages().send(player, if (data.pmEnabled) "pm-on" else "pm-off")
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(reportsNode(plugin))
            .then(
                permLiteral("report", ReportService.MANAGE)
                    .then(
                        Commands.argument("index", IntegerArgumentType.integer(1))
                            .executes { ctx ->
                                plugin.reportService.show(ctx.source.sender, IntegerArgumentType.getInteger(ctx, "index"))
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                permLiteral("spy", "voxen.socialspy")
                    .executes { ctx ->
                        val player = ctx.source.sender as? Player ?: run {
                            plugin.messages().send(ctx.source.sender, "players-only")
                            return@executes Command.SINGLE_SUCCESS
                        }
                        val data = plugin.playerDataService.get(player.uniqueId)
                        data.socialSpy = !data.socialSpy
                        plugin.playerDataService.save(data)
                        plugin.messages().send(player, if (data.socialSpy) "spy-on" else "spy-off")
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()

    private fun reportsNode(plugin: Voxen): LiteralArgumentBuilder<CommandSourceStack> {
        val index = Commands.argument("index", IntegerArgumentType.integer(1))
            .executes { ctx -> reports(plugin, ctx.source.sender, IntegerArgumentType.getInteger(ctx, "index"), false) }
        for (action in ReportService.Action.entries) {
            index.then(
                Commands.literal(action.id).executes { ctx ->
                    plugin.reportService.act(ctx.source.sender, IntegerArgumentType.getInteger(ctx, "index"), action)
                    Command.SINGLE_SUCCESS
                }
            )
        }
        return permLiteral("reports", ReportService.MANAGE)
            .executes { ctx -> reports(plugin, ctx.source.sender, 1, true) }
            .then(index)
    }

    private fun reports(plugin: Voxen, sender: CommandSender, page: Int, dialog: Boolean): Int {
        val viewer = sender as? Player
        if (dialog && viewer != null && plugin.configManager.config.reports.dialogs) {
            plugin.reportService.openQueue(viewer)
        } else {
            plugin.reportService.queue(sender, page)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun sendHelp(plugin: Voxen, sender: CommandSender): Int {
        plugin.messages().send(sender, "help")
        return Command.SINGLE_SUCCESS
    }

    private fun sendStatus(plugin: Voxen, sender: CommandSender): Int {
        val messages = plugin.messages()
        val config = plugin.configManager.config
        messages.send(sender, "status-header", Placeholder.unparsed("version", plugin.pluginMeta.version))
        val (dbQueued, dbDropped, dbMillis) = plugin.playerDataService.stats()
        val (netQueued, netDropped, netLastAt) = plugin.brokerService.stats()
        val lines = listOf(
            "storage" to config.storage.type.name.lowercase(),
            "storage-queue" to "$dbQueued queued, $dbDropped dropped, last write ${dbMillis}ms",
            "network-queue" to "$netQueued queued, $netDropped dropped",
            "network-last-message" to
                if (netLastAt == 0L) "never"
                else "${Durations.humanize(System.currentTimeMillis() - netLastAt)} ago",
            "transport" to if (plugin.brokerService.active()) plugin.brokerService.transportName() else "none",
            "server-id" to config.network.serverId,
            "meta-source" to plugin.hookManager.metaSource,
            "placeholderapi" to (plugin.hookManager.papi != null).toString(),
            "discordsrv" to plugin.hookManager.discord.discordSrv().toString(),
            "essentials-discord" to plugin.hookManager.discord.essentials().toString(),
            "web-panel" to if (plugin.webServer.running()) plugin.webServer.address() else "off",
            "channels" to config.channels.count { it.value.enabled }.toString(),
            "active-mutes" to plugin.muteService.activeMutes().size.toString(),
            "chat-muted" to plugin.muteService.globalChatMuted.toString(),
            "default-language" to config.defaultLanguage,
        )
        for ((key, value) in lines) {
            sender.sendMessage(
                messages.line(
                    sender,
                    "status-line",
                    Placeholder.unparsed("key", key),
                    Placeholder.unparsed("value", value),
                )
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun find(plugin: Voxen, sender: CommandSender, name: String): Int {
        val messages = plugin.messages()
        val server = plugin.server.getPlayerExact(name)?.let { plugin.configManager.config.network.serverId }
            ?: plugin.presenceService.serverOf(name)
        if (server == null) {
            messages.send(sender, "player-not-found", Placeholder.unparsed("player", name))
            return Command.SINGLE_SUCCESS
        }
        messages.send(
            sender,
            "find-result",
            Placeholder.unparsed("player", name),
            Placeholder.unparsed("server", server),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun listPresence(plugin: Voxen, sender: CommandSender): Int {
        val messages = plugin.messages()
        if (!plugin.configManager.config.presence.enabled) {
            messages.send(sender, "find-disabled")
            return Command.SINGLE_SUCCESS
        }
        val entries = plugin.presenceService.entries()
        if (entries.isEmpty()) {
            messages.send(sender, "find-empty")
            return Command.SINGLE_SUCCESS
        }
        messages.send(sender, "find-header", Placeholder.unparsed("amount", entries.size.toString()))
        for (entry in entries) {
            sender.sendMessage(
                messages.line(
                    sender,
                    "find-entry",
                    Placeholder.unparsed("player", entry.name),
                    Placeholder.unparsed("server", entry.server),
                )
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun listMutes(plugin: Voxen, sender: CommandSender): Int {
        val messages = plugin.messages()
        val mutes = plugin.muteService.activeMutes()
        if (mutes.isEmpty()) {
            messages.send(sender, "mute-list-empty")
            return Command.SINGLE_SUCCESS
        }
        messages.send(sender, "mute-list-header", Placeholder.unparsed("amount", mutes.size.toString()))
        for (mute in mutes) {
            sender.sendMessage(
                messages.line(
                    sender,
                    "mute-list-entry",
                    Placeholder.unparsed("player", mute.playerName),
                    Placeholder.unparsed("channel", mute.channel ?: "all"),
                    Placeholder.unparsed("moderator", mute.moderator),
                    Placeholder.unparsed("reason", mute.reason ?: messages.raw(sender, "mute-no-reason")),
                    Placeholder.unparsed(
                        "remaining",
                        mute.expiresAt?.let { Durations.humanize(it - System.currentTimeMillis()) }
                            ?: messages.raw(sender, "mute-permanent"),
                    ),
                )
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun mute(
        plugin: Voxen,
        ctx: CommandContext<CommandSourceStack>,
        duration: String?,
        channelInput: String?,
        reason: String?,
    ): Int = mute(plugin, ctx.source.sender, arg(ctx, "player"), duration, channelInput, reason)

    internal fun mute(
        plugin: Voxen,
        sender: CommandSender,
        name: String,
        duration: String?,
        channelInput: String?,
        reason: String?,
    ): Int {
        val messages = plugin.messages()
        val target = resolve(plugin, name) ?: run {
            messages.send(sender, "player-not-found", Placeholder.unparsed("player", name))
            return Command.SINGLE_SUCCESS
        }
        val online = plugin.server.getPlayer(target.first)
        if (online != null && online.hasPermission("voxen.mute.exempt")) {
            messages.send(sender, "mute-exempt", Placeholder.unparsed("player", target.second))
            return Command.SINGLE_SUCCESS
        }
        val expiresAt = when {
            duration == null || duration.equals("permanent", true) || duration.equals("perm", true) -> null
            else -> Durations.parseMillis(duration)?.let { System.currentTimeMillis() + it } ?: run {
                messages.send(sender, "invalid-duration", Placeholder.unparsed("value", duration))
                return Command.SINGLE_SUCCESS
            }
        }
        val channel = when {
            channelInput == null || channelInput.equals("all", true) -> null
            else -> plugin.channelService.channel(channelInput)?.id ?: run {
                messages.send(sender, "channel-not-found", Placeholder.unparsed("channel", channelInput))
                return Command.SINGLE_SUCCESS
            }
        }
        plugin.muteService.mute(
            MuteEntry(
                uuid = target.first,
                playerName = target.second,
                channel = channel,
                reason = reason?.trim()?.ifEmpty { null },
                moderator = sender.name,
                expiresAt = expiresAt,
                createdAt = System.currentTimeMillis(),
            )
        )
        messages.send(
            sender,
            "muted-player",
            Placeholder.unparsed("player", target.second),
            Placeholder.unparsed("channel", channel ?: "all"),
            Placeholder.unparsed(
                "remaining",
                expiresAt?.let { Durations.humanize(it - System.currentTimeMillis()) } ?: messages.raw(sender, "mute-permanent"),
            ),
        )
        online?.let { messages.send(it, "you-were-muted", Placeholder.unparsed("reason", reason ?: messages.raw(it, "mute-no-reason"))) }
        return Command.SINGLE_SUCCESS
    }

    private fun muteInfo(plugin: Voxen, ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val messages = plugin.messages()
        val name = arg(ctx, "player")
        val target = resolve(plugin, name) ?: run {
            messages.send(sender, "player-not-found", Placeholder.unparsed("player", name))
            return Command.SINGLE_SUCCESS
        }
        val mutes = plugin.muteService.mutesFor(target.first)
        if (mutes.isEmpty()) {
            messages.send(sender, "not-muted", Placeholder.unparsed("player", target.second))
            return Command.SINGLE_SUCCESS
        }
        messages.send(sender, "muteinfo-header", Placeholder.unparsed("player", target.second))
        for (mute in mutes) {
            sender.sendMessage(
                messages.line(
                    sender,
                    "mute-list-entry",
                    Placeholder.unparsed("player", mute.playerName),
                    Placeholder.unparsed("channel", mute.channel ?: "all"),
                    Placeholder.unparsed("moderator", mute.moderator),
                    Placeholder.unparsed("reason", mute.reason ?: messages.raw(sender, "mute-no-reason")),
                    Placeholder.unparsed(
                        "remaining",
                        mute.expiresAt?.let { Durations.humanize(it - System.currentTimeMillis()) }
                            ?: messages.raw(sender, "mute-permanent"),
                    ),
                )
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun slowmode(plugin: Voxen, ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val messages = plugin.messages()
        if (!plugin.configManager.config.moderation.slowmodeEnabled) {
            messages.send(sender, "slowmode-disabled")
            return Command.SINGLE_SUCCESS
        }
        val input = arg(ctx, "channel")
        val channel = plugin.channelService.channel(input) ?: run {
            messages.send(sender, "channel-not-found", Placeholder.unparsed("channel", input))
            return Command.SINGLE_SUCCESS
        }
        val time = arg(ctx, "time")
        val millis = when {
            time == "0" || time.equals("off", true) || time.equals("none", true) -> 0L
            else -> Durations.parseMillis(time) ?: run {
                messages.send(sender, "invalid-duration", Placeholder.unparsed("value", time))
                return Command.SINGLE_SUCCESS
            }
        }
        plugin.muteService.setSlowmode(channel.id, millis)
        val key = if (millis > 0) "slowmode-on" else "slowmode-off"
        val placeholders = arrayOf(
            Placeholder.parsed("channel", channel.displayName),
            Placeholder.unparsed("time", Durations.humanize(millis)),
        )
        val readers = plugin.channelService.readers(channel)
        for (reader in readers) messages.send(reader, key, *placeholders)
        if (readers.none { it.uniqueId == (sender as? Player)?.uniqueId }) messages.send(sender, key, *placeholders)
        return Command.SINGLE_SUCCESS
    }

    private fun history(plugin: Voxen, ctx: CommandContext<CommandSourceStack>): Int {
        val sender = ctx.source.sender
        val messages = plugin.messages()
        val moderation = plugin.configManager.config.moderation
        if (!moderation.historyEnabled) {
            messages.send(sender, "history-disabled")
            return Command.SINGLE_SUCCESS
        }
        val name = arg(ctx, "player")
        val target = resolve(plugin, name) ?: run {
            messages.send(sender, "player-not-found", Placeholder.unparsed("player", name))
            return Command.SINGLE_SUCCESS
        }
        plugin.playerDataService.async { storage ->
            val entries = storage.chatHistory(target.first, moderation.historyEntries)
            plugin.threads.main {
                if (entries.isEmpty()) {
                    messages.send(sender, "history-empty", Placeholder.unparsed("player", target.second))
                    return@main
                }
                messages.send(
                    sender,
                    "history-header",
                    Placeholder.unparsed("player", target.second),
                    Placeholder.unparsed("amount", entries.size.toString()),
                )
                for (entry in entries.asReversed()) {
                    sender.sendMessage(
                        messages.line(
                            sender,
                            "history-entry",
                            Placeholder.unparsed("time", TIME_FORMAT.format(Instant.ofEpochMilli(entry.createdAt))),
                            Placeholder.unparsed("channel", entry.channel),
                            Placeholder.unparsed("server", entry.server),
                            Placeholder.unparsed("message", entry.content),
                        )
                    )
                }
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun unmute(plugin: Voxen, ctx: CommandContext<CommandSourceStack>, channelInput: String?): Int {
        val sender = ctx.source.sender
        val messages = plugin.messages()
        val name = arg(ctx, "player")
        val target = resolve(plugin, name) ?: run {
            messages.send(sender, "player-not-found", Placeholder.unparsed("player", name))
            return Command.SINGLE_SUCCESS
        }
        val removed = when {
            channelInput == null -> plugin.muteService.unmuteAll(target.first) > 0
            channelInput.equals("all", true) -> plugin.muteService.unmute(target.first, null)
            else -> plugin.muteService.unmute(target.first, channelInput.lowercase())
        }
        messages.send(
            sender,
            if (removed) "unmuted-player" else "not-muted",
            Placeholder.unparsed("player", target.second),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun chatClear(plugin: Voxen, sender: CommandSender, targetName: String?): Int {
        val messages = plugin.messages()
        val lines = plugin.configManager.config.moderation.chatClearLines
        val blank = Component.empty()
        if (targetName == null) {
            for (player in plugin.server.onlinePlayers) {
                if (player.hasPermission("voxen.mod.chatclear.exempt")) continue
                repeat(lines) { player.sendMessage(blank) }
                messages.send(player, "chat-cleared", Placeholder.unparsed("moderator", sender.name))
            }
            messages.send(sender, "chat-cleared-by-you")
            return Command.SINGLE_SUCCESS
        }
        val target = plugin.server.getPlayerExact(targetName) ?: run {
            messages.send(sender, "player-not-found", Placeholder.unparsed("player", targetName))
            return Command.SINGLE_SUCCESS
        }
        repeat(lines) { target.sendMessage(blank) }
        messages.send(target, "chat-cleared", Placeholder.unparsed("moderator", sender.name))
        messages.send(sender, "chat-cleared-player", Placeholder.unparsed("player", target.name))
        return Command.SINGLE_SUCCESS
    }

    internal fun resolve(plugin: Voxen, name: String): Pair<UUID, String>? {
        runCatching { UUID.fromString(name) }.getOrNull()?.let { id ->
            return id to (plugin.server.getOfflinePlayer(id).name ?: id.toString())
        }
        plugin.server.getPlayerExact(name)?.let { return it.uniqueId to it.name }
        val offline = plugin.server.getOfflinePlayerIfCached(name) ?: return null
        val offlineName = offline.name ?: return null
        return offline.uniqueId to offlineName
    }

    private fun staff(
        plugin: Voxen,
        ctx: CommandContext<CommandSourceStack>,
        body: (CommandSender, ModeratorService.Target) -> Unit,
    ): Int {
        val sender = ctx.source.sender
        val name = arg(ctx, "player")
        val target = resolve(plugin, name) ?: run {
            plugin.messages().send(sender, "player-not-found", Placeholder.unparsed("player", name))
            return Command.SINGLE_SUCCESS
        }
        body(sender, ModeratorService.Target(target.first, target.second))
        return Command.SINGLE_SUCCESS
    }

    private fun arg(ctx: CommandContext<CommandSourceStack>, name: String): String =
        StringArgumentType.getString(ctx, name)

    private fun permLiteral(name: String, permission: String): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name).requires { it.sender.hasPermission(permission) }

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
}
