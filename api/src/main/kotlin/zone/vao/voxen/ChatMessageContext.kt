package zone.vao.voxen

import java.util.UUID

/**
 * Metadata for one chat line, independent of its format and decorations.
 *
 * [content] is the rendered message body as plain text, without the name,
 * prefix or buttons. Local content follows the viewer's filter setting.
 * It is null when an older remote server did not send the body separately;
 * an empty string is an actual empty body.
 *
 * [messageId] is shared by all viewers and, on current servers, across the
 * network. Legacy non-UUID network ids are mapped to a stable UUID using
 * the source server and id. [senderId], [senderName] and [serverId] may be null for legacy
 * network messages. [isRemote] distinguishes incoming network chat from
 * locally authored chat; it does not depend on whether the author is online.
 */
data class ChatMessageContext(
    val messageId: UUID,
    val senderId: UUID?,
    val senderName: String?,
    val serverId: String?,
    val channelId: String,
    val content: String?,
    val isRemote: Boolean,
)
