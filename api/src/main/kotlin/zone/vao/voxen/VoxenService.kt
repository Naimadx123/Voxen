package zone.vao.voxen

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

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
    fun setActiveChannel(player: Player, channelId: String): Boolean
    fun joinChannel(player: Player, channelId: String): Boolean
    fun leaveChannel(player: Player, channelId: String): Boolean
    fun joinedChannels(player: Player): Collection<ChannelInfo>
    fun sendChannelMessage(player: Player, channelId: String, content: String): Boolean
    fun broadcastToChannel(channelId: String, message: Component): Boolean
    fun isMuted(uuid: UUID): Boolean
    fun isMuted(uuid: UUID, channelId: String): Boolean
    fun isIgnoring(source: UUID, target: UUID): Boolean
    fun sendPrivateMessage(sender: Player, target: Player, content: String): Boolean
    fun nickname(player: Player): String?
    fun setNickname(player: Player, nickname: String?): Boolean
    fun party(member: UUID): PartyInfo?
    fun filterWords(text: String): FilterResult
    fun filterLinks(text: String): FilterResult
    fun isClean(text: String): Boolean
    fun render(player: Player, text: String): Component
    fun stripTags(text: String): String
    fun registerModeratorResolver(prefix: String, resolver: ModeratorResolver): Boolean
    fun unregisterModeratorResolver(prefix: String)
    fun registerPlaceholder(name: String, placeholder: FormatPlaceholder): Boolean
    fun unregisterPlaceholder(name: String)
    fun registerChannel(id: String, displayName: String, format: String, recipients: RecipientProvider?): Boolean
    fun registerChannel(channel: ChannelRegistration): Boolean
    fun unregisterChannel(id: String): Boolean
    fun registerRecipients(channelId: String, provider: RecipientProvider): Boolean
    fun unregisterRecipients(channelId: String)
    fun mute(request: MuteRequest): Boolean
    fun unmute(target: UUID, channelId: String?, moderator: String): Boolean
    fun unmuteAll(target: UUID, moderator: String): Int
    fun warn(target: UUID, targetName: String, reason: String, moderator: String): Boolean
    fun warnings(target: UUID): CompletableFuture<List<WarningInfo>>
    fun activeMutes(target: UUID): List<MuteInfo>
    fun reports(statuses: Collection<ReportInfo.Status>, limit: Int): CompletableFuture<List<ReportInfo>>
    fun report(id: UUID): CompletableFuture<ReportInfo?>
    fun updateReport(id: UUID, action: ReportInfo.Action, moderator: String): CompletableFuture<Boolean>
    fun reportCase(id: UUID): CompletableFuture<ReportCase?>
    fun deleteReportedMessage(id: UUID, moderator: String): CompletableFuture<Boolean>
    fun tickets(statuses: Collection<TicketInfo.Status>, limit: Int): CompletableFuture<List<TicketInfo>>
    fun ticket(id: UUID): CompletableFuture<TicketCase?>
    fun replyToTicket(id: UUID, message: String, moderator: String): CompletableFuture<Boolean>
    fun closeTicket(id: UUID, moderator: String): CompletableFuture<Boolean>
    fun registerPanelPage(id: String, title: Supplier<String>, permission: String, page: PanelPage): Boolean
    fun unregisterPanelPage(id: String): Boolean
    fun serverId(): String
    fun networkConnected(): Boolean
    fun serverOf(name: String): String?
    fun networkPlayers(): Collection<NetworkPlayer>
    fun sendNetworkMessage(channel: String, payload: String, server: String?): Boolean
    fun registerNetworkListener(channel: String, listener: NetworkListener): Boolean
    fun unregisterNetworkListener(channel: String)
    fun reload()
}
