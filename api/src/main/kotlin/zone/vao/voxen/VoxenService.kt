package zone.vao.voxen

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.UUID

/**
 * The Voxen API as an injectable service.
 *
 * Obtain it from Bukkit's ServicesManager
 * (`getServer().getServicesManager().load(VoxenService.class)`) or use the
 * static [VoxenApi] facade, which delegates to the same instance. Method
 * behavior is documented on [VoxenApi].
 */
interface VoxenService {
    fun channels(): Collection<ChannelInfo>
    fun channel(id: String): ChannelInfo?
    fun activeChannel(player: Player): ChannelInfo?
    fun sendChannelMessage(player: Player, channelId: String, content: String): Boolean
    fun broadcastToChannel(channelId: String, message: Component): Boolean
    fun isMuted(uuid: UUID): Boolean
    fun isMuted(uuid: UUID, channelId: String): Boolean
    fun isIgnoring(source: UUID, target: UUID): Boolean
    fun sendPrivateMessage(sender: Player, target: Player, content: String): Boolean
    fun nickname(player: Player): String?
    fun setNickname(player: Player, nickname: String?): Boolean
    fun party(member: UUID): PartyInfo?
    fun registerPlaceholder(name: String, placeholder: FormatPlaceholder): Boolean
    fun unregisterPlaceholder(name: String)
    fun registerChannel(id: String, displayName: String, format: String, recipients: RecipientProvider?): Boolean
    fun unregisterChannel(id: String): Boolean
    fun registerRecipients(channelId: String, provider: RecipientProvider): Boolean
    fun unregisterRecipients(channelId: String)
    fun reload()
}
