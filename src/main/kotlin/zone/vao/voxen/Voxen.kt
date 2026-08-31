package zone.vao.voxen

import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bstats.bukkit.Metrics
import org.bukkit.entity.Player
import zone.vao.voxen.channel.Channel
import zone.vao.voxen.channel.ChannelService
import zone.vao.voxen.chat.ChatDecorators
import zone.vao.voxen.chat.ChatListener
import zone.vao.voxen.chat.ChatService
import zone.vao.voxen.chat.FormatService
import zone.vao.voxen.command.*
import zone.vao.voxen.config.ConfigManager
import zone.vao.voxen.config.Messages
import zone.vao.voxen.config.NetworkConfig
import zone.vao.voxen.hook.HookManager
import zone.vao.voxen.hook.VoxenTags
import zone.vao.voxen.ignore.IgnoreService
import zone.vao.voxen.mail.MailService
import zone.vao.voxen.mention.MentionCompletions
import zone.vao.voxen.mention.MentionService
import zone.vao.voxen.moderation.AiModerationService
import zone.vao.voxen.moderation.ModeratorDialogs
import zone.vao.voxen.moderation.ModeratorService
import zone.vao.voxen.moderation.MuteEntry
import zone.vao.voxen.moderation.MuteService
import zone.vao.voxen.moderation.SpamGuard
import zone.vao.voxen.moderation.WordFilter
import zone.vao.voxen.network.AddonNetwork
import zone.vao.voxen.network.BrokerMessage
import zone.vao.voxen.network.BrokerService
import zone.vao.voxen.party.PartyService
import zone.vao.voxen.presence.PresenceListener
import zone.vao.voxen.report.ReportDialogs
import zone.vao.voxen.report.ReportService
import zone.vao.voxen.report.ReportsWeb
import zone.vao.voxen.system.SystemMessageListener
import zone.vao.voxen.system.SystemMessageService
import zone.vao.voxen.ticket.TicketDialogs
import zone.vao.voxen.ticket.TicketService
import zone.vao.voxen.ticket.TicketsWeb
import zone.vao.voxen.web.WebModule
import zone.vao.voxen.web.WebServer
import zone.vao.voxen.presence.PresenceService
import zone.vao.voxen.pm.PrivateMessageService
import zone.vao.voxen.storage.PlayerDataService
import zone.vao.voxen.storage.PlayerStorage
import zone.vao.voxen.storage.ReportEntry
import zone.vao.voxen.storage.StaffNote
import zone.vao.voxen.storage.TicketEntry
import zone.vao.voxen.storage.StorageConfig
import zone.vao.voxen.storage.StorageFactory
import zone.vao.voxen.storage.StorageType
import zone.vao.voxen.tags.ContentRenderer
import zone.vao.voxen.util.ModeratorNames
import zone.vao.voxen.util.Threads
import zone.vao.voxen.util.UpdateChecker
import zone.vao.voxen.util.Vanish
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

@Suppress("UnstableApiUsage")
class Voxen : org.bukkit.plugin.java.JavaPlugin(), VoxenService {

    lateinit var configManager: ConfigManager
        private set
    lateinit var playerDataService: PlayerDataService
        private set
    lateinit var ignoreService: IgnoreService
        private set
    lateinit var muteService: MuteService
        private set
    lateinit var partyService: PartyService
        private set
    lateinit var hookManager: HookManager
        private set
    lateinit var channelService: ChannelService
        private set
    lateinit var formatService: FormatService
        private set
    lateinit var contentRenderer: ContentRenderer
        private set
    lateinit var chatService: ChatService
        private set
    lateinit var privateMessageService: PrivateMessageService
        private set
    lateinit var brokerService: BrokerService
    lateinit var addonNetwork: AddonNetwork
        private set
    lateinit var presenceService: PresenceService
        private set
    lateinit var mailService: MailService
        private set
    lateinit var moderatorService: ModeratorService
    lateinit var reportService: ReportService
        private set
    lateinit var systemMessageService: SystemMessageService
        private set
    lateinit var ticketService: TicketService
        private set
    lateinit var webServer: WebServer
        private set
    lateinit var aiModerationService: AiModerationService
        private set
    lateinit var mentionCompletions: MentionCompletions
        private set
    lateinit var wordFilter: WordFilter
        private set
    lateinit var threads: Threads
        private set

    private var presenceTask: ScheduledTask? = null

    private val panelPages = ConcurrentHashMap.newKeySet<String>()

    private val componentCodec = GsonComponentSerializer.gson()
    private val miniMessageCodec = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()

    override fun onEnable() {
        configManager = ConfigManager(this) { player ->
            if (::playerDataService.isInitialized) playerDataService.cached(player.uniqueId)?.language else null
        }
        configManager.load()

        threads = Threads(this)
        playerDataService = PlayerDataService(
            this,
            configManager.config.storage.queueSize,
            configManager.config.storage.chatLogBatch,
        )
        server.pluginManager.registerEvents(playerDataService, this)
        playerDataService.attach(createStorage())
        purgeChatLog()

        ignoreService = IgnoreService(playerDataService)
        server.pluginManager.registerEvents(ignoreService, this)
        ignoreService.loadOnline(server.onlinePlayers.map { it.uniqueId })

        muteService = MuteService(server, playerDataService)
        muteService.load()

        partyService = PartyService(server, playerDataService) { configManager.config.party }
        partyService.load()

        hookManager = HookManager(this)
        hookManager.load(configManager.config.integrations)

        channelService = ChannelService(
            server,
            { configManager.config },
            playerDataService,
            ignoreService,
            partyService,
            hookManager.teams,
        )
        contentRenderer = ContentRenderer { configManager.config.tags }
        formatService = FormatService(hookManager, { configManager.config }) { player ->
            if (!configManager.config.nicknames.enabled) null
            else playerDataService.get(player.uniqueId).nickname?.let { nick ->
                contentRenderer.render(nick, player::hasPermission, isPermissionSet = player::isPermissionSet).hoverEvent(
                    configManager.config.messages.line(
                        player, "nickname-hover",
                        Placeholder.unparsed("player", player.name),
                    )
                )
            }
        }
        val spamGuard = SpamGuard({ configManager.config.moderation })
        wordFilter = WordFilter { configManager.config.moderation }
        val mentionService = MentionService({ configManager.config.mentions }, playerDataService)
        mentionCompletions = MentionCompletions(server) { configManager.config.mentions }
        server.pluginManager.registerEvents(mentionCompletions, this)
        chatService = ChatService(
            server,
            { configManager.config },
            channelService,
            formatService,
            contentRenderer,
            muteService,
            spamGuard,
            wordFilter,
            mentionService,
            playerDataService,
            hookManager,
            threads,
            ChatDecorators(logger),
        )
        privateMessageService = PrivateMessageService(
            server,
            { configManager.config },
            playerDataService,
            ignoreService,
            muteService,
            spamGuard,
            wordFilter,
            contentRenderer,
            threads,
        )

        brokerService = BrokerService(
            logger,
            { configManager.config.network },
            configManager.config.network.queueSize,
        )
        addonNetwork = AddonNetwork(brokerService, { configManager.config.network.serverId }, logger)
        brokerService.onAddonMessage = { message -> threads.main { addonNetwork.deliver(message) } }
        chatService.remotePublisher = { channel, player, component, content ->
            brokerService.publish(
                BrokerMessage(
                    id = UUID.randomUUID().toString(),
                    server = configManager.config.network.serverId,
                    channel = channel.id,
                    sender = player.name,
                    component = componentCodec.serialize(component),
                    content = content.ifEmpty { null },
                    mm = miniMessageCodec.serialize(component),
                    senderUuid = player.uniqueId.toString(),
                    flags = buildList {
                        if (player.hasPermission(ChannelService.BYPASS_IGNORE)) add("ignore")
                        if (player.hasPermission(ChannelService.BYPASS_CHAT_TOGGLE)) add("chattoggle")
                    }.joinToString(",").ifEmpty { null },
                )
            )
        }
        presenceService = PresenceService(
            { configManager.config.presence },
            { configManager.config.network.serverId },
        )
        presenceService.remotePublisher = { message -> brokerService.publish(message) }
        brokerService.onPresenceMessage = { message -> presenceService.handleRemote(message) }
        server.pluginManager.registerEvents(PresenceListener(presenceService), this)
        privateMessageService.routeLookup = { name -> presenceService.serverOf(name) }
        startPresenceHeartbeat()

        systemMessageService = SystemMessageService(
            server,
            { configManager.config },
            channelService,
            chatService,
            formatService,
            { hookManager.discord },
            presenceService,
        )
        systemMessageService.remotePublisher = { message -> brokerService.publish(message) }
        systemMessageService.schedule = { delayMillis, task ->
            server.asyncScheduler.runDelayed(
                this,
                { threads.main { task() } },
                delayMillis,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }
        brokerService.onSystemMessage = { message -> systemMessageService.handleRemote(message) }
        presenceService.onRemoteJoin = { uuid -> systemMessageService.cancelQuit(uuid) }
        server.pluginManager.registerEvents(SystemMessageListener(systemMessageService), this)

        privateMessageService.remotePublisher = { message ->
            brokerService.publish(message.copy(server = configManager.config.network.serverId))
        }
        privateMessageService.scheduleTimeout = { delayMillis, task ->
            server.asyncScheduler.runDelayed(
                this,
                { threads.main { task() } },
                delayMillis,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }
        brokerService.onPmMessage = { message ->
            threads.main { privateMessageService.handleRemote(message) }
        }
        muteService.remotePublisher = { message ->
            if (configManager.config.network.syncMutes) {
                brokerService.publish(message.copy(server = configManager.config.network.serverId))
            }
        }
        brokerService.onModerationMessage = { message ->
            if (configManager.config.network.syncMutes) muteService.handleRemote(message)
        }
        brokerService.onChatMessage = { message ->
            val channel = message.channel?.let { channelService.channel(it) }
            val component = when {
                channel?.externalFormat != null && message.sender != null && message.content != null ->
                    formatService.renderExternal(channel, message.sender, message.server.orEmpty(), message.content)

                else -> message.mm?.let { runCatching { miniMessageCodec.deserialize(it) }.getOrNull() }
                    ?: runCatching { componentCodec.deserialize(message.component!!) }.getOrNull()
            }
            if (component != null && message.channel != null) {
                chatService.deliverRemote(
                    message.channel,
                    component,
                    message.content,
                    senderUuid = runCatching { UUID.fromString(message.senderUuid) }.getOrNull(),
                    bypassIgnore = "ignore" in message.flags.orEmpty().split(','),
                    bypassChatToggle = "chattoggle" in message.flags.orEmpty().split(','),
                )
            }
        }
        brokerService.start()

        mailService = MailService(
            server,
            { configManager.config },
            playerDataService,
            privateMessageService,
            threads,
        ) { name -> presenceService.serverOf(name) != null }
        server.pluginManager.registerEvents(mailService, this)
        purgeMail()

        moderatorService = ModeratorService(
            server,
            { configManager.config },
            playerDataService,
            muteService,
            presenceService,
            threads,
        )
        val dialogs = ModeratorDialogs(this)
        moderatorService.dialogs = { viewer, target, lines -> dialogs.inspect(viewer, target, lines) }
        moderatorService.warnDialog = { viewer, target -> dialogs.warn(viewer, target) }
        moderatorService.notesDialog = { viewer, target, lines -> dialogs.notes(viewer, target, lines) }
        server.pluginManager.registerEvents(moderatorService, this)
        purgeWarnings()
        reportService = ReportService(server, { configManager.config }, playerDataService, moderatorService, threads)
        val reportDialogs = ReportDialogs(this)
        reportService.submitDialog = { viewer, target, reference -> reportDialogs.submit(viewer, target, reference) }
        reportService.queueDialog = { viewer, entries -> reportDialogs.queue(viewer, entries) }
        reportService.viewDialog = { viewer, case -> reportDialogs.view(viewer, case) }
        reportService.historyDialog = { viewer, case -> reportDialogs.history(viewer, case) }
        reportService.muteAction = { sender, name, duration, reason ->
            VoxenCommand.mute(this, sender, name, duration, "all", reason)
        }
        chatService.onMessage = { out -> reportService.remember(out.id, out.player, out.channel.id, out.content) }
        chatService.messageButton = { out, viewer -> reportService.chatButton(out.player, out.id, viewer) }
        purgeReports()

        ticketService = TicketService(server, { configManager.config }, playerDataService, threads)
        val ticketDialogs = TicketDialogs(this)
        ticketService.panelDialog = { viewer, entries -> ticketDialogs.panel(viewer, entries) }
        ticketService.viewDialog = { viewer, case -> ticketDialogs.view(viewer, case) }
        ticketService.purge()

        webServer = WebServer(logger) { configManager.config.web }
        webServer.register(ReportsWeb.module(this))
        webServer.register(ReportsWeb.auditModule(this))
        webServer.register(TicketsWeb.module(this))
        webServer.start()

        aiModerationService = AiModerationService(server, { configManager.config }, moderatorService, threads, logger)
        server.pluginManager.registerEvents(
            ChatListener(
                chatService,
                { configManager.config.chatDelivery },
                moderatorService,
                aiModerationService,
                reportService,
            ),
            this,
        )

        VoxenApi.init(this)
        server.servicesManager.register(VoxenService::class.java, this, this, org.bukkit.plugin.ServicePriority.Normal)
        registerPlaceholders()
        registerCommands()

        val updateChecker = UpdateChecker(this)
        server.pluginManager.registerEvents(updateChecker, this)
        updateChecker.start()

        Metrics(this, 33423)

        logger.info("Voxen enabled — ${configManager.config.channels.count { it.value.enabled }} channel(s) loaded.")
    }

    override fun onDisable() {
        presenceTask?.cancel()
        ModeratorNames.clear()
        if (::webServer.isInitialized) webServer.stop()
        if (::brokerService.isInitialized) brokerService.shutdown()
        if (::playerDataService.isInitialized) playerDataService.shutdown()
        if (::aiModerationService.isInitialized) aiModerationService.shutdown()
        VoxenApi.shutdown()
    }

    override fun reload() {
        configManager.load()
        hookManager.load(configManager.config.integrations)
        muteService.load()
        partyService.load()
        brokerService.start()
        webServer.start()
        presenceService.clear()
        startPresenceHeartbeat()
        mentionCompletions.refresh()
        refreshClientCommands()
    }

    fun messages(): Messages = configManager.config.messages

    override fun channels(): Collection<ChannelInfo> =
        channelService.channels().values.map(::info)

    override fun channel(id: String): ChannelInfo? =
        channelService.channel(id)?.let(::info)

    override fun activeChannel(player: Player): ChannelInfo? =
        threads.awaitPlayer(player) { channelService.activeChannel(player)?.let(::info) }

    override fun setActiveChannel(player: Player, channelId: String): Boolean =
        threads.awaitPlayer(player) {
            val channel = channelService.channel(channelId) ?: return@awaitPlayer false
            channelService.setActive(player, channel)
        } ?: false

    override fun joinChannel(player: Player, channelId: String): Boolean =
        threads.awaitPlayer(player) {
            val channel = channelService.channel(channelId) ?: return@awaitPlayer false
            channelService.join(player, channel)
        } ?: false

    override fun leaveChannel(player: Player, channelId: String): Boolean =
        threads.awaitPlayer(player) {
            val channel = channelService.channel(channelId) ?: return@awaitPlayer false
            channelService.leave(player, channel)
        } ?: false

    override fun joinedChannels(player: Player): Collection<ChannelInfo> =
        threads.awaitPlayer(player) { channelService.joinedChannels(player).map(::info) } ?: emptyList()

    override fun filterWords(text: String): FilterResult = verdict(wordFilter.check(text), text)

    override fun filterLinks(text: String): FilterResult = verdict(wordFilter.checkLinks(text), text)

    override fun isClean(text: String): Boolean = filterWords(text).isClean() && filterLinks(text).isClean()

    override fun render(player: Player, text: String): Component =
        threads.awaitPlayer(player) {
            contentRenderer.render(text, player::hasPermission, isPermissionSet = player::isPermissionSet)
        } ?: Component.text(text)

    override fun stripTags(text: String): String = contentRenderer.plain(text)

    private fun verdict(result: WordFilter.Result, original: String): FilterResult = when (result) {
        WordFilter.Result.Clean -> FilterResult(FilterResult.Verdict.CLEAN, original)
        WordFilter.Result.Blocked -> FilterResult(FilterResult.Verdict.BLOCKED, original)
        is WordFilter.Result.Censored -> FilterResult(FilterResult.Verdict.CENSORED, result.content)
    }

    override fun sendChannelMessage(player: Player, channelId: String, content: String): Boolean {
        val channel = channelService.channel(channelId) ?: return false
        return chatService.send(player, channel, content)
    }

    override fun broadcastToChannel(channelId: String, message: Component): Boolean {
        val channel = channelService.channel(channelId) ?: return false
        if (!channel.enabled) return false
        return chatService.broadcast(channel, message)
    }

    override fun isMuted(uuid: UUID): Boolean = muteService.isMuted(uuid, null)

    override fun isMuted(uuid: UUID, channelId: String): Boolean = muteService.isMuted(uuid, channelId)

    override fun isIgnoring(source: UUID, target: UUID): Boolean = ignoreService.isIgnoring(source, target)

    override fun sendPrivateMessage(sender: Player, target: Player, content: String): Boolean =
        threads.awaitPlayer(sender) { privateMessageService.send(sender, target, content) } ?: false

    override fun nickname(player: Player): String? = playerDataService.get(player.uniqueId).nickname

    override fun setNickname(player: Player, nickname: String?): Boolean {
        val config = configManager.config.nicknames
        if (!config.enabled) return false
        if (nickname != null) {
            val visible = contentRenderer.plain(nickname)
            if (visible.length < config.minLength || visible.length > config.maxLength) return false
            if (config.filter && wordFilter.check(visible) != WordFilter.Result.Clean) return false
        }
        val data = playerDataService.get(player.uniqueId)
        data.nickname = nickname
        playerDataService.save(data)
        return true
    }

    override fun party(member: UUID): PartyInfo? = partyService.partyOf(member)?.let {
        PartyInfo(id = it.id, name = it.name, leader = it.leader, members = it.members)
    }

    override fun registerModeratorResolver(prefix: String, resolver: ModeratorResolver): Boolean =
        ModeratorNames.register(prefix, resolver)

    override fun unregisterModeratorResolver(prefix: String) {
        ModeratorNames.unregister(prefix)
    }

    override fun registerPlaceholder(name: String, placeholder: FormatPlaceholder): Boolean =
        formatService.registerPlaceholder(name, placeholder)

    override fun unregisterPlaceholder(name: String) {
        formatService.unregisterPlaceholder(name)
    }

    override fun registerChannel(id: String, displayName: String, format: String, recipients: RecipientProvider?): Boolean {
        if (!channelService.registerApiChannel(id, displayName, format)) return false
        recipients?.let { channelService.registerRecipients(id, it) }
        return true
    }

    override fun registerChannel(channel: ChannelRegistration): Boolean =
        registerChannel(channel.id, channel.displayName, channel.format, channel.recipients)

    override fun unregisterChannel(id: String): Boolean = channelService.unregisterApiChannel(id)

    override fun serverId(): String = configManager.config.network.serverId

    override fun networkConnected(): Boolean = brokerService.active()

    override fun serverOf(name: String): String? =
        server.getPlayerExact(name)?.let { configManager.config.network.serverId }
            ?: presenceService.serverOf(name)

    override fun networkPlayers(): Collection<NetworkPlayer> {
        val here = configManager.config.network.serverId
        val now = System.currentTimeMillis()
        val local = server.onlinePlayers
            .filterNot(Vanish::hidden)
            .map { NetworkPlayer(it.uniqueId, it.name, here, now) }
        val remote = presenceService.entries()
            .map { NetworkPlayer(it.uuid, it.name, it.server, it.seenAt) }
        return (local + remote).distinctBy { it.uuid }
    }

    override fun registerChatDecorator(id: String, decorator: ChatDecorator): Boolean =
        chatService.decorators.register(id, decorator)

    override fun unregisterChatDecorator(id: String) {
        chatService.decorators.unregister(id)
    }

    override fun sendNetworkMessage(channel: String, payload: String, server: String?): Boolean =
        addonNetwork.send(channel, payload, server)

    override fun registerNetworkListener(channel: String, listener: NetworkListener): Boolean =
        addonNetwork.register(channel, listener)

    override fun unregisterNetworkListener(channel: String) {
        addonNetwork.unregister(channel)
    }

    override fun registerRecipients(channelId: String, provider: RecipientProvider): Boolean =
        channelService.registerRecipients(channelId, provider)

    override fun unregisterRecipients(channelId: String) {
        channelService.unregisterRecipients(channelId)
    }

    override fun mute(request: MuteRequest): Boolean {
        val channel = request.channelId?.let { channelService.channel(it)?.id ?: return false }
        val now = System.currentTimeMillis()
        return muteService.mute(
            MuteEntry(
                uuid = request.target,
                playerName = request.targetName,
                channel = channel,
                reason = request.reason?.trim()?.ifEmpty { null },
                moderator = request.moderator,
                expiresAt = if (request.durationMillis > 0L) now + request.durationMillis else null,
                createdAt = now,
            )
        )
    }

    override fun unmute(target: UUID, channelId: String?, moderator: String): Boolean =
        muteService.unmute(target, channelId?.lowercase(), moderator)

    override fun unmuteAll(target: UUID, moderator: String): Int = muteService.unmuteAll(target, moderator)

    override fun warn(target: UUID, targetName: String, reason: String, moderator: String): Boolean =
        moderatorService.warn(moderator, ModeratorService.Target(target, targetName), reason)

    override fun warnings(target: UUID): CompletableFuture<List<WarningInfo>> = onStorage { storage ->
        storage.staffNotes(target, StaffNote.Kind.WARN, configManager.config.moderatorTools.warningCutoff)
            .map { note ->
                WarningInfo(
                    id = note.id,
                    target = note.target,
                    targetName = note.targetName,
                    moderator = note.author,
                    reason = note.content,
                    createdAt = note.createdAt,
                )
            }
    }

    override fun activeMutes(target: UUID): List<MuteInfo> = muteService.mutesFor(target).map { mute ->
        MuteInfo(
            target = mute.uuid,
            targetName = mute.playerName,
            channelId = mute.channel,
            reason = mute.reason,
            moderator = mute.moderator,
            expiresAt = mute.expiresAt,
            createdAt = mute.createdAt,
        )
    }

    override fun reportCase(id: UUID): CompletableFuture<ReportCase?> = onStorage {
        reportService.case(id)?.let { loaded ->
            ReportCase(
                report = reportService.info(loaded.entry),
                context = loaded.context.map { line ->
                    ChatLine(
                        id = line.id,
                        player = line.uuid,
                        playerName = line.playerName,
                        channelId = line.channel,
                        content = line.content,
                        server = line.server,
                        createdAt = line.createdAt,
                    )
                },
                history = loaded.actions.map { action ->
                    ReportCase.AuditEntry(
                        id = action.id,
                        actor = action.actor,
                        action = action.action,
                        detail = action.detail,
                        createdAt = action.createdAt,
                    )
                },
            )
        }
    }

    override fun deleteReportedMessage(id: UUID, moderator: String): CompletableFuture<Boolean> =
        onStorage { reportService.deleteReported(moderator, id) }

    override fun tickets(statuses: Collection<TicketInfo.Status>, limit: Int): CompletableFuture<List<TicketInfo>> {
        val wanted = statuses.map { TicketEntry.Status.valueOf(it.name) }
        return onStorage { storage -> storage.tickets(wanted, limit).map(ticketService::info) }
    }

    override fun ticket(id: UUID): CompletableFuture<TicketCase?> = onStorage { storage ->
        storage.ticket(id)?.let { entry ->
            val limit = configManager.config.helpop.historyLimit
            TicketCase(ticketService.info(entry), storage.ticketMessages(id, limit).map(ticketService::info))
        }
    }

    override fun replyToTicket(id: UUID, message: String, moderator: String): CompletableFuture<Boolean> =
        onStorage { ticketService.answer(moderator, id, message) }

    override fun closeTicket(id: UUID, moderator: String): CompletableFuture<Boolean> =
        onStorage { ticketService.close(moderator, id) }

    override fun reports(statuses: Collection<ReportInfo.Status>, limit: Int): CompletableFuture<List<ReportInfo>> {
        val wanted = statuses.map { ReportEntry.Status.valueOf(it.name) }
        return onStorage { storage -> storage.reports(wanted, limit).map(reportService::info) }
    }

    override fun report(id: UUID): CompletableFuture<ReportInfo?> =
        onStorage { storage -> storage.report(id)?.let(reportService::info) }

    override fun updateReport(id: UUID, action: ReportInfo.Action, moderator: String): CompletableFuture<Boolean> {
        val choice = ReportService.Action.entries.first { it.info == action }
        return onStorage { reportService.apply(moderator, id, choice) }
    }

    private fun <T> onStorage(task: (PlayerStorage) -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val queued = playerDataService.async { storage ->
            runCatching { task(storage) }.fold(future::complete, future::completeExceptionally)
        }
        if (!queued) future.completeExceptionally(IllegalStateException("Voxen storage is not available."))
        return future
    }

    override fun registerPanelPage(
        id: String,
        title: Supplier<String>,
        permission: String,
        page: PanelPage,
    ): Boolean {
        val lower = id.lowercase()
        if (!lower.matches(Regex("[a-z0-9_-]+")) || permission.isBlank()) return false
        val added = webServer.register(
            WebModule(
                id = lower,
                title = { title.get() },
                permission = permission,
                enabled = { page.enabled() },
                render = { request -> page.render(request) },
                submit = { request -> page.submit(request) },
                handle = { request -> page.handle(request) },
            )
        )
        if (added) panelPages.add(lower)
        return added
    }

    override fun unregisterPanelPage(id: String): Boolean {
        val lower = id.lowercase()
        return panelPages.remove(lower) && webServer.unregister(lower)
    }

    private fun info(channel: Channel): ChannelInfo = ChannelInfo(
        id = channel.id,
        displayName = channel.displayName,
        type = channel.type.name.lowercase(),
        enabled = channel.enabled,
        readOnly = channel.readOnly,
        crossServer = channel.crossServer,
        radius = channel.radius,
        worlds = channel.worlds,
    )

    private fun createStorage(): zone.vao.voxen.storage.PlayerStorage {
        val config = configManager.config.storage
        return runCatching { StorageFactory.create(this, config) }.getOrElse { first ->
            if (config.type != StorageType.SQLITE && !config.sqliteFallback) {
                throw IllegalStateException(
                    "Failed to initialise ${config.type.name.lowercase()} storage: ${first.message}. " +
                        "Fix the connection or set sqlite-fallback: true in storage.yml.",
                    first,
                )
            }
            logger.warning("Failed to initialise ${config.type.name.lowercase()} storage (${first.message}); falling back to SQLite.")
            StorageFactory.create(
                this,
                config.copy(
                    type = StorageType.SQLITE,
                    username = "",
                    password = "",
                    poolSize = 1,
                ),
            )
        }
    }

    private fun networked(): Boolean =
        configManager.config.network.transport != NetworkConfig.Transport.NONE

    private fun startPresenceHeartbeat() {
        presenceTask?.cancel()
        presenceTask = null
        val presence = configManager.config.presence
        if (!presence.enabled || !networked()) return
        presenceTask = server.globalRegionScheduler.runAtFixedRate(
            this,
            {
                if (brokerService.active() && configManager.config.presence.enabled) {
                    presenceService.announceRoster(
                        server.onlinePlayers.filterNot(Vanish::hidden).map { it.uniqueId to it.name },
                    )
                }
            },
            1L,
            (presence.heartbeatMillis / 50L).coerceAtLeast(20L),
        )
    }

    private fun purgeChatLog() {
        val moderation = configManager.config.moderation
        if (!moderation.historyEnabled || moderation.historyKeepDays <= 0) return
        val cutoff = System.currentTimeMillis() - moderation.historyKeepDays * 86_400_000L
        playerDataService.async { it.purgeChatLog(cutoff) }
    }

    private fun purgeMail() {
        val mail = configManager.config.mail
        if (!mail.enabled || mail.expireDays <= 0) return
        val cutoff = System.currentTimeMillis() - mail.expireDays * 86_400_000L
        playerDataService.async { it.purgeMail(cutoff) }
    }

    private fun purgeReports() {
        val reports = configManager.config.reports
        if (!reports.enabled || reports.expireDays <= 0) return
        val cutoff = System.currentTimeMillis() - reports.expireDays * 86_400_000L
        playerDataService.async { it.purgeReports(cutoff) }
    }

    private fun purgeWarnings() {
        val tools = configManager.config.moderatorTools
        if (!tools.enabled || !tools.warningsEnabled || tools.warningExpireMillis <= 0L) return
        val cutoff = tools.warningCutoff
        playerDataService.async { it.purgeStaffNotes(cutoff, StaffNote.Kind.WARN) }
    }

    private fun refreshClientCommands() {
        for (player in server.onlinePlayers) {
            player.scheduler.run(this, { player.updateCommands() }, null)
        }
    }

    private fun registerPlaceholders() {
        val integrations = configManager.config.integrations
        if (integrations.placeholderApi && server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
            runCatching {
                val expansion = Class.forName("zone.vao.voxen.hook.VoxenExpansion")
                    .getConstructor(Voxen::class.java)
                    .newInstance(this)
                expansion.javaClass.getMethod("register").invoke(expansion)
            }.onFailure { logger.warning("Failed to register the PlaceholderAPI expansion: ${it.message}") }
        }
        if (integrations.miniPlaceholders && server.pluginManager.isPluginEnabled("MiniPlaceholders")) {
            runCatching { VoxenTags.register(this) }
                .onFailure { logger.warning("Failed to register the MiniPlaceholders expansion: ${it.message}") }
        }
    }

    private fun registerCommands() {
        val commands = configManager.config.commands
        val channels = configManager.config.channels
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            val reserved = mutableSetOf("voxen")
            registrar.register(VoxenCommand.build(this), "Voxen chat administration", listOf())

            fun register(names: List<String>, description: String, build: (String) -> LiteralCommandNode<CommandSourceStack>) {
                val primary = names.firstOrNull()?.lowercase() ?: return
                if (!reserved.add(primary)) {
                    logger.warning("Command '/$primary' clashes with another Voxen command; skipping it.")
                    return
                }
                val aliases = names.drop(1).map { it.lowercase() }.filter { reserved.add(it) }
                registrar.register(build(primary), description, aliases)
            }

            register(commands.message, "Send a private message") { MessageCommand.buildMessage(this, it) }
            register(commands.reply, "Reply to the last private message") { MessageCommand.buildReply(this, it) }
            register(commands.channel, "Manage your chat channels") { ChannelCommand.build(this, it) }
            register(commands.ignore, "Ignore or unignore a player") { IgnoreCommand.buildIgnore(this, it) }
            register(commands.ignoreList, "List ignored players") { IgnoreCommand.buildIgnoreList(this, it) }
            register(commands.chatToggle, "Toggle chat visibility") { ToggleCommands.buildChatToggle(this, it) }
            register(commands.language, "Choose your Voxen language") { ToggleCommands.buildLanguage(this, it) }
            register(commands.filter, "Toggle the chat filter for yourself") { ToggleCommands.buildFilterToggle(this, it) }
            if (configManager.config.helpop.enabled) {
                register(commands.helpop, "Ask staff for help") { HelpopCommand.build(this, it) }
            }
            if (configManager.config.reports.enabled) {
                register(commands.report, "Report a player to the staff") { ReportCommand.build(this, it) }
            }
            if (configManager.config.nicknames.enabled) {
                register(commands.nickname, "Manage nicknames") { NickCommand.build(this, it) }
                register(commands.realName, "Look up who is using a nickname") { NickCommand.buildRealName(this, it) }
            }
            if (configManager.config.party.enabled) {
                register(commands.party, "Manage your party") { PartyCommand.build(this, it) }
            }
            if (configManager.config.mail.enabled) {
                register(commands.mail, "Send and read offline mail") { MailCommand.build(this, it) }
            }

            for (channel in channels.values) {
                if (!channel.enabled) continue
                for (alias in channel.aliases) {
                    if (!reserved.add(alias)) {
                        logger.warning("Channel '${channel.id}' alias '/$alias' clashes with another Voxen command; skipping it.")
                        continue
                    }
                    registrar.register(
                        ChannelCommand.buildAlias(this, alias, channel.id),
                        "Talk in the '${channel.id}' channel",
                    )
                }
            }
        }
    }
}
