package zone.vao.voxen

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.jetbrains.annotations.ApiStatus
import java.util.UUID
import java.util.concurrent.CompletableFuture

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

    /** Reloads the Voxen configuration, same as `/voxen reload`. */
    @JvmStatic
    fun reload() {
        service().reload()
    }
}
