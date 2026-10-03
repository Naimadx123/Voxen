package zone.vao.voxen.network

import zone.vao.voxen.ChatMessageContext
import java.util.UUID
data class BrokerMessage(
    val id: String?,
    val server: String?,
    val channel: String?,
    val sender: String?,
    val component: String?,
    val content: String? = null,
    val mm: String? = null,
    val type: String? = null,
    val target: String? = null,
    val senderUuid: String? = null,
    val targetUuid: String? = null,
    val status: String? = null,
    val replyTo: String? = null,
    val flags: String? = null,
    val route: String? = null,
    val roster: List<String>? = null,
    val expiresAt: Long? = null,
    val createdAt: Long? = null,
    val chatContent: String? = null,
) {
    /** Keeps legacy mention content separate from the body used by addons. */
    fun chatContext(): ChatMessageContext = ChatMessageContext(
        messageId = runCatching { UUID.fromString(id) }.getOrElse {
            UUID.nameUUIDFromBytes("$server:$id".toByteArray(Charsets.UTF_8))
        },
        senderId = runCatching { UUID.fromString(senderUuid) }.getOrNull(),
        senderName = sender,
        serverId = server,
        channelId = requireNotNull(channel),
        content = chatContent ?: content,
        isRemote = true,
    )
}
