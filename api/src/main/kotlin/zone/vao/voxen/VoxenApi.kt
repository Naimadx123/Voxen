package zone.vao.voxen

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.jetbrains.annotations.ApiStatus
import java.util.UUID

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
 * All methods are safe to call from any thread unless noted otherwise.
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
    fun registerChannel(id: String, displayName: String, format: String, recipients: RecipientProvider? = null): Boolean =
        service().registerChannel(id, displayName, format, recipients)

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

    /** Reloads the Voxen configuration, same as `/voxen reload`. */
    @JvmStatic
    fun reload() {
        service().reload()
    }
}
