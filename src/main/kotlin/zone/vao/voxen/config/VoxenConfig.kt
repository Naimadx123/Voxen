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
    val storage: StorageConfig,
    val commands: CommandNames,
    val nicknames: NicknamesConfig,
    val emotes: EmotesConfig,
    val chatDelivery: ChatDelivery,
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
