package zone.vao.voxen.config

import zone.vao.voxen.channel.Channel
import zone.vao.voxen.storage.StorageConfig

data class VoxenConfig(
    val serverName: String,
    val defaultLanguage: String,
    val quickChatEnabled: Boolean,
    val itemShare: ItemShareConfig,
    val messages: Messages,
    val channels: Map<String, Channel>,
    val moderation: ModerationConfig,
    val mentions: MentionsConfig,
    val privateMessages: PrivateMessagesConfig,
    val tags: TagsConfig,
    val party: PartyConfig,
    val integrations: IntegrationsConfig,
    val network: NetworkConfig,
    val presence: PresenceConfig,
    val mail: MailConfig,
    val moderatorTools: ModeratorToolsConfig,
    val aiModeration: AiModerationConfig,
    val storage: StorageConfig,
    val commands: CommandNames,
    val nicknames: NicknamesConfig,
    val emotes: EmotesConfig,
    val chatDelivery: ChatDelivery,
    val updateChecker: UpdateCheckerConfig,
)

data class PresenceConfig(
    val enabled: Boolean,
    val heartbeatMillis: Long,
    val ttlMillis: Long,
    val suggestRemotePlayers: Boolean,
)

data class UpdateCheckerConfig(
    val enabled: Boolean,
    val notify: Boolean,
)

enum class ChatDelivery {
    SYSTEM,
    PLAYER;

    companion object {
        fun from(value: String?): ChatDelivery =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: SYSTEM
    }
}

data class NicknamesConfig(
    val enabled: Boolean,
    val minLength: Int,
    val maxLength: Int,
    val filter: Boolean,
)
