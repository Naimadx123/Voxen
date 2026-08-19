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
import zone.vao.voxen.chat.ChatListener
import zone.vao.voxen.chat.ChatService
import zone.vao.voxen.chat.FormatService
import zone.vao.voxen.command.*
import zone.vao.voxen.config.ConfigManager
import zone.vao.voxen.config.Messages
import zone.vao.voxen.hook.HookManager
import zone.vao.voxen.hook.VoxenTags
import zone.vao.voxen.ignore.IgnoreService
import zone.vao.voxen.mail.MailService
import zone.vao.voxen.mention.MentionService
import zone.vao.voxen.moderation.ModeratorDialogs
import zone.vao.voxen.moderation.ModeratorService
import zone.vao.voxen.moderation.MuteService
import zone.vao.voxen.moderation.SpamGuard
import zone.vao.voxen.moderation.WordFilter
import zone.vao.voxen.network.BrokerMessage
import zone.vao.voxen.network.BrokerService
import zone.vao.voxen.party.PartyService
import zone.vao.voxen.presence.PresenceListener
import zone.vao.voxen.presence.PresenceService
import zone.vao.voxen.pm.GroupService
import zone.vao.voxen.pm.PrivateMessageService
import zone.vao.voxen.storage.PlayerDataService
import zone.vao.voxen.storage.StaffNote
import zone.vao.voxen.storage.StorageConfig
import zone.vao.voxen.storage.StorageFactory
import zone.vao.voxen.storage.StorageType
import zone.vao.voxen.tags.ContentRenderer
import zone.vao.voxen.util.Threads
import zone.vao.voxen.util.UpdateChecker
import zone.vao.voxen.util.Vanish
import java.util.*

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
        private set
    lateinit var presenceService: PresenceService
        private set
    lateinit var mailService: MailService
        private set
    lateinit var groupService: GroupService
        private set
    lateinit var moderatorService: ModeratorService
        private set
    lateinit var wordFilter: WordFilter
        private set
    lateinit var threads: Threads
        private set

    private var presenceTask: ScheduledTask? = null

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

        muteService = MuteService(playerDataService)
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
                    flags = if (player.hasPermission(ChannelService.BYPASS_IGNORE)) "ignore" else null,
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
        groupService = GroupService(
            server,
            { configManager.config },
            privateMessageService,
            ignoreService,
            presenceService,
            threads,
        )
        groupService.remotePublisher = { message -> brokerService.publish(message) }
        brokerService.onPmMessage = { message ->
            if (message.type == BrokerService.TYPE_PM_GROUP) groupService.handleRemote(message)
            else threads.main { privateMessageService.handleRemote(message) }
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
                // external-format is a receiver-side format, so the raw text is re-rendered here
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
        server.pluginManager.registerEvents(
            ChatListener(chatService, { configManager.config.chatDelivery }, moderatorService),
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
        if (::brokerService.isInitialized) brokerService.shutdown()
        if (::playerDataService.isInitialized) playerDataService.shutdown()
        VoxenApi.shutdown()
    }

    override fun reload() {
        configManager.load()
        hookManager.load(configManager.config.integrations)
        muteService.load()
        partyService.load()
        brokerService.start()
        presenceService.clear()
        groupService.clear()
        startPresenceHeartbeat()
        refreshClientCommands()
    }

    fun messages(): Messages = configManager.config.messages

    override fun channels(): Collection<ChannelInfo> =
        channelService.channels().values.map(::info)

    override fun channel(id: String): ChannelInfo? =
        channelService.channel(id)?.let(::info)

    override fun activeChannel(player: Player): ChannelInfo? =
        channelService.activeChannel(player)?.let(::info)

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
        privateMessageService.send(sender, target, content)

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

    override fun unregisterChannel(id: String): Boolean = channelService.unregisterApiChannel(id)

    override fun registerRecipients(channelId: String, provider: RecipientProvider): Boolean =
        channelService.registerRecipients(channelId, provider)

    override fun unregisterRecipients(channelId: String) {
        channelService.unregisterRecipients(channelId)
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
                // silently writing to a local file would split data across servers, so refuse to start
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

    private fun startPresenceHeartbeat() {
        presenceTask?.cancel()
        presenceTask = null
        val presence = configManager.config.presence
        if (!presence.enabled) return
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
            if (configManager.config.nicknames.enabled) {
                register(commands.nickname, "Manage nicknames") { NickCommand.build(this, it) }
                register(commands.realName, "Look up who is using a nickname") { NickCommand.buildRealName(this, it) }
            }
            if (configManager.config.party.enabled) {
                register(commands.party, "Manage your party") { PartyCommand.build(this, it) }
            }
            if (configManager.config.privateMessages.group.enabled) {
                register(commands.group, "Group private messages") { GroupCommand.build(this, it) }
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
