package zone.vao.voxen

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.jetbrains.annotations.ApiStatus
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

/**
 * Static entry point to the Voxen API.
 *
 * Available from the moment Voxen finishes enabling, so call it from your
 * plugin's `onEnable` (with a `depend`/`softdepend` on Voxen in plugin.yml)
 * or later. Calling any method before that throws [IllegalStateException].
 *
 * If you prefer dependency injection over statics, the same interface is
 * registered in Bukkit's ServicesManager:
 *
 * ```java
 * VoxenService voxen = getServer().getServicesManager().load(VoxenService.class);
 * ```
 *
 * Methods that take a [org.bukkit.entity.Player] read that player's
 * permissions and state, so Voxen runs them on the thread that owns them:
 * the server thread on Paper, that player's region on Folia. Calling one
 * from anywhere else works, but the call waits for the hop, so keep those
 * off hot paths.
 *
 * Everything else — mutes, reports, network lookups, panel pages, the text
 * filter — is safe from any thread.
 */
object VoxenApi {

    @Volatile
    private var service: VoxenService? = null

    private fun service(): VoxenService =
        service ?: error("Voxen is not enabled yet; use the API from your plugin's onEnable or later.")

    @ApiStatus.Internal
    fun init(service: VoxenService) {
        this.service = service
    }

    @ApiStatus.Internal
    fun shutdown() {
        service = null
    }

    /**
     * Returns true once Voxen has enabled and the API is usable. Check this
     * first in a `softdepend` plugin; every other method throws until it does.
     */
    @JvmStatic
    fun isAvailable(): Boolean = service != null

    /** Returns a snapshot of every configured channel, including disabled ones. */
    @JvmStatic
    fun channels(): Collection<ChannelInfo> = service().channels()

    /** Returns the channel with the given id (lowercase), or null if it does not exist. */
    @JvmStatic
    fun channel(id: String): ChannelInfo? = service().channel(id)

    /** Returns the channel the player is currently talking in, or null if they have none. */
    @JvmStatic
    fun activeChannel(player: Player): ChannelInfo? = service().activeChannel(player)

    /**
     * Moves the player to another channel, exactly as the channel command
     * does: they join it if they had not, and it becomes the one they talk
     * in. [zone.vao.voxen.event.ChannelSwitchEvent] fires first.
     *
     * Returns false when the channel does not exist, is disabled, the player
     * may not join it, or a handler cancelled the switch.
     */
    @JvmStatic
    fun setActiveChannel(player: Player, channelId: String): Boolean =
        service().setActiveChannel(player, channelId)

    /**
     * Adds the player to a channel without changing the one they talk in.
     * Returns false when the channel does not exist or they may not join it.
     */
    @JvmStatic
    fun joinChannel(player: Player, channelId: String): Boolean = service().joinChannel(player, channelId)

    /**
     * Removes the player from a channel. If it was the one they were talking
     * in, they fall back to the default. Returns false when they were not in
     * it to begin with.
     */
    @JvmStatic
    fun leaveChannel(player: Player, channelId: String): Boolean = service().leaveChannel(player, channelId)

    /** Returns the channels the player is in and may read, in config order. */
    @JvmStatic
    fun joinedChannels(player: Player): Collection<ChannelInfo> = service().joinedChannels(player)

    /**
     * Sends a chat message on behalf of the player, exactly as if they typed it
     * into the given channel. The full chat pipeline applies: write permission,
     * mutes, cooldowns, the word filter and tag permissions in [content].
     *
     * [content] is raw message text and may contain MiniMessage tags; the
     * player's tag permissions decide which of them render.
     *
     * Returns false when the channel does not exist or the message was rejected
     * (muted player, cooldown, blocked words and so on). The player receives the
     * matching feedback message in both cases.
     */
    @JvmStatic
    fun sendChannelMessage(player: Player, channelId: String, content: String): Boolean =
        service().sendChannelMessage(player, channelId, content)

    /**
     * Delivers a ready-made component to everyone who can read the channel,
     * bypassing formats, filters and mutes. Returns false when the channel
     * does not exist or is disabled.
     */
    @JvmStatic
    fun broadcastToChannel(channelId: String, message: Component): Boolean =
        service().broadcastToChannel(channelId, message)

    /** Returns true when the player is muted globally (in all channels). */
    @JvmStatic
    fun isMuted(uuid: UUID): Boolean = service().isMuted(uuid)

    /** Returns true when the player is muted in the given channel, globally or per channel. */
    @JvmStatic
    fun isMuted(uuid: UUID, channelId: String): Boolean = service().isMuted(uuid, channelId)

    /** Returns true when [source] has [target] on their ignore list. */
    @JvmStatic
    fun isIgnoring(source: UUID, target: UUID): Boolean = service().isIgnoring(source, target)

    /**
     * Sends a private message from [sender] to [target], exactly as if they
     * used the message command. All checks apply: the target's PM toggle,
     * ignore lists and tag permissions in [content]. Also updates the reply
     * target for both players.
     *
     * Returns false when the message was rejected; the sender receives the
     * matching feedback message.
     */
    @JvmStatic
    fun sendPrivateMessage(sender: Player, target: Player, content: String): Boolean =
        service().sendPrivateMessage(sender, target, content)

    /** Returns the player's raw nickname (may contain MiniMessage tags), or null when unset. */
    @JvmStatic
    fun nickname(player: Player): String? = service().nickname(player)

    /**
     * Sets or clears the player's nickname; null removes it. No permission
     * checks are made, but the length limits from config.yml still apply
     * (counted on the visible text, tags excluded).
     *
     * Returns false when nicknames are disabled or the length is out of range.
     */
    @JvmStatic
    fun setNickname(player: Player, nickname: String?): Boolean = service().setNickname(player, nickname)

    /** Returns the party the player belongs to, or null. Snapshot, see [PartyInfo]. */
    @JvmStatic
    fun party(member: UUID): PartyInfo? = service().party(member)

    /**
     * Runs Voxen's word filter over any text, so a sign, a book or an auction
     * name can be held to the same rules as chat. Reads
     * `modules/moderation.yml`, and answers CLEAN while the filter is off.
     */
    @JvmStatic
    fun filterWords(text: String): FilterResult = service().filterWords(text)

    /** The same for the link filter. */
    @JvmStatic
    fun filterLinks(text: String): FilterResult = service().filterLinks(text)

    /** True when neither filter has anything against the text. */
    @JvmStatic
    fun isClean(text: String): Boolean = service().isClean(text)

    /**
     * Renders MiniMessage and legacy `&` codes the way Voxen renders chat:
     * tags the player has no permission for are stripped or escaped, exactly
     * as `modules/minimessage-tags.yml` says.
     *
     * Use this instead of MiniMessage directly whenever the text came from a
     * player, or they can colour text your plugin never meant them to.
     */
    @JvmStatic
    fun render(player: Player, text: String): Component = service().render(player, text)

    /** Strips every MiniMessage tag, leaving the readable text. */
    @JvmStatic
    fun stripTags(text: String): String = service().stripTags(text)

    /**
     * Teaches Voxen to print moderator names that your addon wrote.
     *
     * A name stored as `discord:123456789` is shown through the resolver
     * registered for `discord`, which is handed the `123456789` half and
     * answers with something readable like `4g0 (Naimad123)`. It applies
     * everywhere Voxen prints who did what: report and ticket screens, the
     * web panel, audit trails, mute and warning lists.
     *
     * What events and the API hand back is always the stored string, so the
     * prefix stays usable as a loop guard.
     *
     * Returns false when the prefix is invalid; only `a-z`, `0-9`, `-` and
     * `_` are allowed. Registering an existing prefix replaces its resolver.
     */
    @JvmStatic
    fun registerModeratorResolver(prefix: String, resolver: ModeratorResolver): Boolean =
        service().registerModeratorResolver(prefix, resolver)

    /** Removes a resolver registered with [registerModeratorResolver]. */
    @JvmStatic
    fun unregisterModeratorResolver(prefix: String) {
        service().unregisterModeratorResolver(prefix)
    }

    /**
     * Registers a custom placeholder usable in chat formats as `<name>`.
     * The resolver runs for every message, so keep it fast and thread safe.
     *
     * Returns false when the name is invalid; only `a-z`, `0-9` and `_` are
     * allowed. Registering an existing name replaces the previous resolver.
     */
    @JvmStatic
    fun registerPlaceholder(name: String, placeholder: FormatPlaceholder): Boolean =
        service().registerPlaceholder(name, placeholder)

    /** Removes a placeholder registered with [registerPlaceholder]. Unknown names are ignored. */
    @JvmStatic
    fun unregisterPlaceholder(name: String) {
        service().unregisterPlaceholder(name)
    }

    /**
     * Rewrites every chat line before it reaches a viewer, so you can hang a
     * badge, a marker or a button off somebody else's message. Voxen already
     * builds each line per viewer, so this costs nothing extra to hook.
     *
     * [id] is yours and must be `[a-z0-9_.-]`, up to 64 characters; prefix it
     * with your plugin name. Registering a second decorator under the same id
     * returns false and keeps the first. Decorators run in registration
     * order, each one receiving what the last returned.
     *
     * Read [ChatDecorator] before you write one: it runs once per viewer per
     * message on the chat thread, which rules out most of the Bukkit API.
     */
    @JvmStatic
    fun registerChatDecorator(id: String, decorator: ChatDecorator): Boolean =
        service().registerChatDecorator(id, decorator)

    /** Removes a decorator registered with [registerChatDecorator]. Unknown ids are ignored. */
    @JvmStatic
    fun unregisterChatDecorator(id: String) {
        service().unregisterChatDecorator(id)
    }

    /**
     * Registers a channel owned by your plugin. It behaves like a configured
     * channel: players can join it, talk in it and it shows up in [channels].
     *
     * [format] is a MiniMessage string with the usual placeholders
     * (`<player>`, `<message>`, `<prefix>` and so on). When [recipients] is
     * null every online player receives the messages.
     *
     * Returns false when the id is already taken or invalid (allowed:
     * `a-z`, `0-9`, `-`, `_`). API channels disappear on server restart, so
     * register them on every startup. Unregister with [unregisterChannel]
     * when your plugin disables.
     */
    @JvmStatic
    @JvmOverloads
    @Deprecated(
        "Build the channel with ChannelRegistration.builder instead.",
        ReplaceWith("registerChannel(ChannelRegistration.builder(id, displayName).format(format).recipients(recipients).build())"),
    )
    fun registerChannel(id: String, displayName: String, format: String, recipients: RecipientProvider? = null): Boolean =
        service().registerChannel(id, displayName, format, recipients)

    /**
     * The same, with the parts named instead of lined up. Every channel is
     * gone on restart, so register on every startup and drop it with
     * [unregisterChannel] when your plugin disables.
     */
    @JvmStatic
    fun registerChannel(channel: ChannelRegistration): Boolean = service().registerChannel(channel)

    /** Removes a channel registered with [registerChannel]. Returns false for unknown or configured channels. */
    @JvmStatic
    fun unregisterChannel(id: String): Boolean = service().unregisterChannel(id)

    /**
     * Overrides who receives messages in the given channel. Works for API
     * channels and configured ones. Returns false when the channel does not exist.
     */
    @JvmStatic
    fun registerRecipients(channelId: String, provider: RecipientProvider): Boolean =
        service().registerRecipients(channelId, provider)

    /** Removes a recipient override, restoring the channel's normal audience. */
    @JvmStatic
    fun unregisterRecipients(channelId: String) {
        service().unregisterRecipients(channelId)
    }

    /**
     * Mutes a player, exactly as the mute command does: it is stored, it
     * reaches the rest of the network, and
     * [zone.vao.voxen.event.PlayerMuteEvent] fires first.
     *
     * Build the request with [MuteRequest.builder]. Returns false when the
     * channel does not exist or a handler cancelled the mute.
     */
    @JvmStatic
    fun mute(request: MuteRequest): Boolean = service().mute(request)

    /**
     * Lifts one mute; [channelId] null lifts the one covering every channel.
     * Returns false when there was nothing to lift or a handler cancelled it.
     */
    @JvmStatic
    fun unmute(target: UUID, channelId: String?, moderator: String): Boolean =
        service().unmute(target, channelId, moderator)

    /** Lifts every mute the player has and returns how many were removed. */
    @JvmStatic
    fun unmuteAll(target: UUID, moderator: String): Int = service().unmuteAll(target, moderator)

    /**
     * Warns a player, exactly as the warn command does: it goes on their
     * record, the warning rules for that count run, and
     * [zone.vao.voxen.event.PlayerWarnEvent] fires first.
     *
     * [moderator] is the name written into the record; see
     * [externalModerator] for acting on behalf of someone outside the game.
     * Returns false when warnings are turned off or a handler cancelled it.
     */
    @JvmStatic
    fun warn(target: UUID, targetName: String, reason: String, moderator: String): Boolean =
        service().warn(target, targetName, reason, moderator)

    /** Reads a player's warnings that are still counted, newest first. */
    @JvmStatic
    fun warnings(target: UUID): CompletableFuture<List<WarningInfo>> = service().warnings(target)

    /** Reads the mutes a player currently has. Answers from memory, so no future. */
    @JvmStatic
    fun activeMutes(target: UUID): List<MuteInfo> = service().activeMutes(target)

    /**
     * Reads the reports still waiting for a moderator, newest first.
     *
     * Every report method talks to the database, so it answers through a
     * [CompletableFuture] instead of blocking the caller. Handle the result
     * with `thenAccept`, or `join()` it when you are already off the server
     * thread.
     */
    @JvmStatic
    fun reports(limit: Int): CompletableFuture<List<ReportInfo>> =
        service().reports(ReportInfo.Status.PENDING, limit)

    /**
     * Reads reports with any of the given statuses, newest first. An empty
     * collection means every status.
     */
    @JvmStatic
    fun reports(statuses: Collection<ReportInfo.Status>, limit: Int): CompletableFuture<List<ReportInfo>> =
        service().reports(statuses, limit)

    /** Reads one report, or null when it is gone. */
    @JvmStatic
    fun report(id: UUID): CompletableFuture<ReportInfo?> = service().report(id)

    /**
     * Claims, resolves, dismisses or deletes a report under [moderator]'s
     * name, exactly as the queue commands do: the audit trail records it and
     * [zone.vao.voxen.event.ReportUpdateEvent] fires first.
     *
     * Completes with false when the report is gone or already has that
     * status, and when a handler cancelled the event.
     */
    @JvmStatic
    fun updateReport(id: UUID, action: ReportInfo.Action, moderator: String): CompletableFuture<Boolean> =
        service().updateReport(id, action, moderator)

    /**
     * Reads one report with everything around it: the chat before and after
     * the reported message, and the audit trail of what moderators did.
     *
     * How much context comes back is `context` in `modules/reports.yml`.
     * Completes with null when the report is gone.
     */
    @JvmStatic
    fun reportCase(id: UUID): CompletableFuture<ReportCase?> = service().reportCase(id)

    /**
     * Deletes the chat message a report points at, for everyone who can still
     * see it, and records it in the report's audit trail.
     *
     * Only works while the message is still deletable: signed messages are
     * kept in memory on the server they were typed on, so this completes with
     * false after a restart or from another server on the network.
     */
    @JvmStatic
    fun deleteReportedMessage(id: UUID, moderator: String): CompletableFuture<Boolean> =
        service().deleteReportedMessage(id, moderator)

    /**
     * Reads help tickets, newest activity first. Pass
     * [TicketInfo.Status.ACTIVE] for the queue, or an empty collection for
     * every status.
     *
     * Always empty while `modules/helpop.yml` runs in broadcast mode.
     */
    @JvmStatic
    fun tickets(statuses: Collection<TicketInfo.Status>, limit: Int): CompletableFuture<List<TicketInfo>> =
        service().tickets(statuses, limit)

    /** Reads one ticket with its whole conversation, or null when it is gone. */
    @JvmStatic
    fun ticket(id: UUID): CompletableFuture<TicketCase?> = service().ticket(id)

    /**
     * Answers a ticket as [moderator]. The player is told in game when they
     * are online, and [zone.vao.voxen.event.TicketUpdateEvent] fires.
     *
     * Completes with false when the ticket is gone or already closed.
     */
    @JvmStatic
    fun replyToTicket(id: UUID, message: String, moderator: String): CompletableFuture<Boolean> =
        service().replyToTicket(id, message, moderator)

    /** Closes a ticket. Completes with false when it is gone or already closed. */
    @JvmStatic
    fun closeTicket(id: UUID, moderator: String): CompletableFuture<Boolean> =
        service().closeTicket(id, moderator)

    /**
     * Builds a moderator name for someone acting from outside the game, like
     * `discord:123456789`, for the `moderator` argument every moderation
     * method takes.
     *
     * Voxen stores it as written and hands it back on the matching event, so
     * an addon can recognise its own doing and not act on it twice. That is
     * the whole loop guard: check the prefix before reacting to an event.
     */
    @JvmStatic
    fun externalModerator(system: String, id: String): String =
        "${system.trim().lowercase()}:${id.trim()}"

    /**
     * Adds a page to the web panel. It appears in the sidebar as [title] for
     * every account holding [permission] in `modules/web.yml`, and answers
     * with 403 for the rest.
     *
     * Returns false when [id] is taken or invalid (allowed: `a-z`, `0-9`,
     * `-`, `_`). Pages are not written to disk, so register them on every
     * startup and drop them with [unregisterPanelPage] on disable.
     */
    @JvmStatic
    fun registerPanelPage(id: String, title: String, permission: String, page: PanelPage): Boolean =
        service().registerPanelPage(id, { title }, permission, page)

    /**
     * Same, with a title read again on every request. Use it to take the title
     * from your own config so a reload renames the sidebar entry.
     */
    @JvmStatic
    fun registerPanelPage(id: String, title: Supplier<String>, permission: String, page: PanelPage): Boolean =
        service().registerPanelPage(id, title, permission, page)

    /** Removes a page registered with [registerPanelPage]. Returns false for unknown or built-in pages. */
    @JvmStatic
    fun unregisterPanelPage(id: String): Boolean = service().unregisterPanelPage(id)

    /** This server's network id, as set by `network.server-id` in integrations.yml. */
    @JvmStatic
    fun serverId(): String = service().serverId()

    /**
     * True while this server is connected to the network broker. False on a
     * single server, and while the transport is down.
     */
    @JvmStatic
    fun networkConnected(): Boolean = service().networkConnected()

    /**
     * Returns the network id of the server the named player is on, or null
     * when nobody on the network answers to that name. Answers for vanished
     * players too, so a message can still be routed to them.
     */
    @JvmStatic
    fun serverOf(name: String): String? = service().serverOf(name)

    /**
     * Everyone on the network, this server included, vanished players left
     * out. Without a network it is simply the local player list.
     *
     * Remote entries come from presence tracking, so they need
     * `modules/presence.yml` on; a server that crashes has its players drop
     * off once its entries go stale.
     */
    @JvmStatic
    fun networkPlayers(): Collection<NetworkPlayer> = service().networkPlayers()

    /**
     * Sends your own payload to the other servers on the Voxen network, over
     * whichever transport `integrations.yml` configures. Voxen signs it,
     * stamps it against replays and retries the connection for you, so an
     * addon does not need its own Redis.
     *
     * [channel] is yours to choose and must be `[a-z0-9_.-]`, up to 64
     * characters. Prefix it with your plugin name so two addons cannot
     * collide, for example `myshop.sales`. [payload] is any text you like,
     * up to 64 KiB; JSON is the obvious choice.
     *
     * Pass a network id as [server] to reach one server, or leave it out to
     * reach all of them. The sending server never hears its own message.
     *
     * Returns false when the network is off or unreachable, and when the
     * channel or payload is refused. True means Voxen accepted it for
     * delivery, not that it arrived; sending is asynchronous and never
     * blocks the caller.
     */
    @JvmStatic
    @JvmOverloads
    fun sendNetworkMessage(channel: String, payload: String, server: String? = null): Boolean =
        service().sendNetworkMessage(channel, payload, server)

    /**
     * Listens for [sendNetworkMessage] payloads on [channel]. The listener
     * runs on the server thread, so Bukkit calls are safe.
     *
     * One listener per channel; registering a second one for the same
     * channel returns false and keeps the first. Returns false for a channel
     * name that does not match the rules in [sendNetworkMessage].
     *
     * Unregister in your plugin's `onDisable`, otherwise a reload leaves a
     * listener pointing at your old classes.
     */
    @JvmStatic
    fun registerNetworkListener(channel: String, listener: NetworkListener): Boolean =
        service().registerNetworkListener(channel, listener)

    /** Removes a listener registered with [registerNetworkListener]. Unknown channels are ignored. */
    @JvmStatic
    fun unregisterNetworkListener(channel: String) {
        service().unregisterNetworkListener(channel)
    }

    /** Reloads the Voxen configuration, same as `/voxen reload`. */
    @JvmStatic
    fun reload() {
        service().reload()
    }
}
