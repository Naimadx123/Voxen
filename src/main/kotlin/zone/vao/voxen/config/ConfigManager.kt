package zone.vao.voxen.config

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import zone.vao.voxen.channel.Channel
import zone.vao.voxen.channel.ChannelService
import zone.vao.voxen.channel.ChannelType
import zone.vao.voxen.network.Envelope
import zone.vao.voxen.network.ProxySecret
import zone.vao.voxen.storage.StorageConfig
import zone.vao.voxen.storage.StorageType
import zone.vao.voxen.util.Durations
import java.io.File
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class ConfigManager(
    private val plugin: JavaPlugin,
    private val languageOverride: (Player) -> String?,
) {

    @Volatile
    lateinit var config: VoxenConfig
        private set

    fun load() {
        saveDefaults()

        val main = YamlConfiguration.loadConfiguration(file("config.yml"))
        val storage = YamlConfiguration.loadConfiguration(file("storage.yml"))
        val integrations = YamlConfiguration.loadConfiguration(file("integrations.yml"))
        val moderation = YamlConfiguration.loadConfiguration(file("modules/moderation.yml"))
        val mentions = YamlConfiguration.loadConfiguration(file("modules/mentions.yml"))
        val privateMessages = YamlConfiguration.loadConfiguration(file("modules/private-messages.yml"))
        val tags = YamlConfiguration.loadConfiguration(file("modules/minimessage-tags.yml"))
        val party = YamlConfiguration.loadConfiguration(file("modules/party.yml"))
        val emotes = YamlConfiguration.loadConfiguration(file("modules/emotes.yml"))
        val presence = YamlConfiguration.loadConfiguration(file("modules/presence.yml"))
        val mail = YamlConfiguration.loadConfiguration(file("modules/mail.yml"))
        val moderatorTools = YamlConfiguration.loadConfiguration(file("modules/moderator-tools.yml"))
        val reports = YamlConfiguration.loadConfiguration(file("modules/reports.yml"))
        val web = YamlConfiguration.loadConfiguration(file("modules/web.yml"))
        val aiModeration = YamlConfiguration.loadConfiguration(file("modules/ai-moderation.yml"))
        val systemMessages = YamlConfiguration.loadConfiguration(file("modules/system-messages.yml"))
        val helpop = YamlConfiguration.loadConfiguration(file("modules/helpop.yml"))

        config = VoxenConfig(
            serverName = main.getString("server-name")?.trim()?.ifEmpty { null } ?: "server",
            defaultLanguage = main.getString("default-language")?.trim()?.ifEmpty { null } ?: "en_US",
            quickChatEnabled = main.getBoolean("quick-chat", true),
            itemShare = parseItemShare(main),
            messages = loadMessages(main),
            channels = loadChannels(),
            moderation = parseModeration(moderation),
            mentions = parseMentions(mentions),
            privateMessages = parsePrivateMessages(privateMessages),
            tags = parseTags(tags),
            party = parseParty(party),
            emotes = parseEmotes(emotes),
            integrations = parseIntegrations(integrations),
            network = parseNetwork(integrations.getConfigurationSection("network")),
            presence = parsePresence(presence),
            systemMessages = parseSystemMessages(systemMessages),
            helpop = parseHelpop(helpop),
            mail = parseMail(mail),
            moderatorTools = parseModeratorTools(moderatorTools),
            reports = parseReports(reports),
            web = parseWeb(web),
            aiModeration = parseAiModeration(aiModeration),
            storage = parseStorage(storage),
            commands = parseCommands(main.getConfigurationSection("commands")),
            chatDelivery = ChatDelivery.from(main.getString("chat-delivery")),
            nicknames = NicknamesConfig(
                enabled = main.getBoolean("nicknames.enabled", true),
                minLength = main.getInt("nicknames.min-length", 3).coerceAtLeast(1),
                maxLength = main.getInt("nicknames.max-length", 24).coerceAtLeast(1),
                filter = main.getBoolean("nicknames.filter", true),
            ),
            updateChecker = UpdateCheckerConfig(
                enabled = main.getBoolean("update-checker.enabled", true),
                notify = main.getBoolean("update-checker.notify", true),
            ),
        )
    }

    private fun saveDefaults() {
        for (name in DEFAULT_FILES) syncDefaults(name)
        if (!File(plugin.dataFolder, CHANNELS_DIR).isDirectory) {
            for (name in DEFAULT_CHANNELS) plugin.saveResource("$CHANNELS_DIR/$name", false)
        }
    }

    private fun syncDefaults(name: String) {
        val target = file(name)
        if (!target.exists()) {
            plugin.saveResource(name, false)
            return
        }

        val resource = plugin.getResource(name) ?: return
        val defaults = resource.bufferedReader(Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
        val current = YamlConfiguration.loadConfiguration(target)

        val missing = defaults.getKeys(true)
            .filterNot { defaults.isConfigurationSection(it) }
            .filterNot { current.contains(it) }
        if (missing.isEmpty()) return

        for (key in missing) {
            current.set(key, defaults.get(key))
            current.setComments(key, defaults.getComments(key))
            current.setInlineComments(key, defaults.getInlineComments(key))
        }
        runCatching { current.save(target) }
            .onSuccess { plugin.logger.info("Added ${missing.size} new default value(s) to $name.") }
            .onFailure { plugin.logger.warning("Failed to update $name with new defaults: ${it.message}") }
    }

    private fun file(name: String) = File(plugin.dataFolder, name)

    private fun loadMessages(main: YamlConfiguration): Messages {
        val dir = File(plugin.dataFolder, MESSAGES_DIR)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".yml") }?.sortedBy { it.name } ?: emptyList()
        val locales = buildMap {
            for (localeFile in files) {
                val yaml = YamlConfiguration.loadConfiguration(localeFile)
                val prefix = yaml.getString("prefix") ?: ""
                val raw = buildMap {
                    yaml.getKeys(false)
                        .filter { it != "prefix" }
                        .forEach { key -> yaml.getString(key)?.let { put(key, it) } }
                }
                put(localeFile.nameWithoutExtension, Messages.LocaleBundle(prefix, raw))
            }
        }
        val defaultLanguage = main.getString("default-language")?.trim()?.ifEmpty { null } ?: "en_US"
        if (locales.isEmpty()) plugin.logger.warning("$MESSAGES_DIR: no language files found; raw message keys will be shown.")
        if (locales.keys.none { it.equals(defaultLanguage, ignoreCase = true) }) {
            plugin.logger.warning("config.yml: default-language '$defaultLanguage' has no $MESSAGES_DIR/$defaultLanguage.yml file; falling back to en_US.")
        }
        return Messages(defaultLanguage, locales, languageOverride)
    }

    private fun loadChannels(): Map<String, Channel> {
        val dir = File(plugin.dataFolder, CHANNELS_DIR)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".yml") }?.sortedBy { it.name } ?: return emptyMap()
        val channels = buildMap {
            for (channelFile in files) {
                val id = channelFile.nameWithoutExtension.lowercase()
                if (!id.matches(Regex("[a-z0-9_-]+"))) {
                    plugin.logger.warning("$CHANNELS_DIR/${channelFile.name}: channel id may only contain [a-z0-9_-]; skipping it.")
                    continue
                }
                put(id, parseChannel(id, channelFile.name, YamlConfiguration.loadConfiguration(channelFile)))
            }
        }
        if (channels.values.none { it.enabled && it.defaultChannel }) {
            plugin.logger.warning("$CHANNELS_DIR: no enabled channel has 'default: true'; new players will not join any channel.")
        }
        return channels
    }

    private fun parseChannel(id: String, fileName: String, section: ConfigurationSection): Channel {
        val type = ChannelType.from(section.getString("type"))
        if (section.getString("type") != null && type == ChannelType.CUSTOM &&
            !section.getString("type").equals("custom", ignoreCase = true)
        ) {
            plugin.logger.warning("$CHANNELS_DIR/$fileName: unknown type '${section.getString("type")}'; treating the channel as custom.")
        }
        val radius = section.getInt("radius", -1)
        if (radius < -1) plugin.logger.warning("$CHANNELS_DIR/$fileName: 'radius' must be -1 or positive; using -1.")
        val cooldown = section.getString("cooldown")?.trim().orEmpty().let { raw ->
            if (raw.isEmpty()) 0L else Durations.parseMillis(raw) ?: run {
                plugin.logger.warning("$CHANNELS_DIR/$fileName: invalid 'cooldown' value '$raw'; ignoring it.")
                0L
            }
        }
        val scope = section.getString("scope")?.trim()?.lowercase()?.ifEmpty { null }
        if (scope != null && scope !in VALID_SCOPES) {
            plugin.logger.warning("$CHANNELS_DIR/$fileName: unknown scope '$scope' (expected one of $VALID_SCOPES); ignoring it.")
        }
        val localOnly = ChannelService.localOnlyReason(type, radius, scope?.takeIf { it in VALID_SCOPES })
        var crossServer = section.getBoolean("cross-server", type == ChannelType.SERVER)
        if (crossServer && localOnly != null) {
            plugin.logger.warning(
                "$CHANNELS_DIR/$fileName: 'cross-server' does not work with $localOnly, because the other " +
                    "servers cannot check it. Turning cross-server off for this channel.",
            )
            crossServer = false
        }
        return Channel(
            id = id,
            displayName = section.getString("display-name")?.ifEmpty { null } ?: id,
            type = type,
            enabled = section.getBoolean("enabled", true),
            defaultChannel = section.getBoolean("default", false),
            defaultActive = section.getBoolean("default-active", false),
            readOnly = section.getBoolean("read-only", false),
            crossServer = crossServer,
            radius = radius.coerceAtLeast(-1),
            worlds = section.getStringList("worlds").mapTo(LinkedHashSet()) { it.trim() },
            format = section.getString("format")?.ifEmpty { null }
                ?: "<gray>[<white><channel></white>]</gray> <white><player></white><dark_gray>:</dark_gray> <message>",
            groupFormats = parseGroupFormats(section.getConfigurationSection("group-formats")),
            worldFormats = parseGroupFormats(section.getConfigurationSection("world-formats")),
            consoleFormat = section.getString("console-format")?.trim()?.ifEmpty { null },
            externalFormat = section.getString("external-format")?.trim()?.ifEmpty { null },
            aliases = section.getStringList("aliases").mapNotNull { alias ->
                alias.removePrefix("/").trim().lowercase().ifEmpty { null }
            },
            quickPrefix = section.getString("quick-prefix")?.trim()?.ifEmpty { null },
            cooldownMillis = cooldown,
            readPermission = parsePermission(section.getString("permissions.read")),
            writePermission = parsePermission(section.getString("permissions.write")),
            joinPermission = parsePermission(section.getString("permissions.join")),
            managePermission = parsePermission(section.getString("permissions.manage")),
            emptyWarning = section.getBoolean("empty-warning", false),
            itemTags = section.getBoolean("item-tags", true),
            scope = scope?.takeIf { it in VALID_SCOPES },
            discord = section.getBoolean("discord", false),
            discordFormat = section.getString("discord-format")?.trim()?.ifEmpty { null },
            sound = parseSound("$CHANNELS_DIR/$fileName", section.getConfigurationSection("sound")),
        )
    }

    private fun parsePermission(raw: String?): String? =
        raw?.trim()?.lowercase()?.ifEmpty { null }?.takeUnless { it == "none" }

    private fun parseGroupFormats(section: ConfigurationSection?): Map<String, String> {
        if (section == null) return emptyMap()
        return buildMap {
            for (key in section.getKeys(false)) {
                section.getString(key)?.let { put(key.lowercase(), it) }
            }
        }
    }

    private fun parseModeration(yaml: YamlConfiguration): ModerationConfig {
        val patterns = mutableListOf<Pattern>()
        for (raw in yaml.getStringList("filter.patterns")) {
            try {
                patterns += Pattern.compile(raw, Pattern.CASE_INSENSITIVE)
            } catch (ex: PatternSyntaxException) {
                plugin.logger.warning("modules/moderation.yml: invalid regex in 'filter.patterns' ('$raw'): ${ex.description}; skipping it.")
            }
        }
        val cooldown = yaml.getString("cooldown")?.trim().orEmpty().let { raw ->
            if (raw.isEmpty()) 0L else Durations.parseMillis(raw) ?: run {
                plugin.logger.warning("modules/moderation.yml: invalid 'cooldown' value '$raw'; ignoring it.")
                0L
            }
        }
        return ModerationConfig(
            cooldownMillis = cooldown,
            repeatEnabled = yaml.getBoolean("anti-repeat.enabled", true),
            repeatWindowMillis = (yaml.getLong("anti-repeat.window-seconds", 30L).coerceAtLeast(0L)) * 1000L,
            similarityEnabled = yaml.getBoolean("anti-repeat.similarity.enabled", false),
            similarityThreshold = yaml.getDouble("anti-repeat.similarity.threshold", 0.85).coerceIn(0.0, 1.0),
            similarityHistory = yaml.getInt("anti-repeat.similarity.history", 3).coerceIn(1, 20),
            floodEnabled = yaml.getBoolean("anti-flood.enabled", true),
            floodMaxRun = yaml.getInt("anti-flood.max-run", 8).coerceAtLeast(0),
            floodMaxWordLength = yaml.getInt("anti-flood.max-word-length", 30).coerceAtLeast(0),
            filterEnabled = yaml.getBoolean("filter.enabled", true),
            filterMode = ModerationConfig.FilterMode.from(yaml.getString("filter.mode")),
            censorReplacement = yaml.getString("filter.censor-char")?.firstOrNull() ?: '*',
            blockedWords = loadBlockedWords(yaml),
            blockedPatterns = patterns,
            chatClearLines = yaml.getInt("chat-clear-lines", 100).coerceIn(1, 500),
            normalizeLeet = yaml.getBoolean("filter.normalize.leetspeak", true),
            normalizeDiacritics = yaml.getBoolean("filter.normalize.diacritics", true),
            normalizeSeparators = yaml.getBoolean("filter.normalize.separators", true),
            normalizeRepeated = yaml.getBoolean("filter.normalize.repeated", true),
            glyphsEnabled = yaml.getBoolean("glyphs.enabled", false),
            glyphMode = ModerationConfig.FilterMode.from(yaml.getString("glyphs.mode")?.ifEmpty { "censor" } ?: "censor"),
            glyphsAffectsPm = yaml.getBoolean("glyphs.affects-private-messages", true),
            linksEnabled = yaml.getBoolean("links.enabled", false),
            linkMode = ModerationConfig.FilterMode.from(yaml.getString("links.mode")),
            linkIps = yaml.getBoolean("links.ips", true),
            linkObfuscated = yaml.getBoolean("links.obfuscated", true),
            linkWhitelist = yaml.getStringList("links.whitelist")
                .mapNotNull { it.trim().lowercase().removePrefix("*.").ifEmpty { null } }
                .toSet(),
            slowmodeEnabled = yaml.getBoolean("slowmode.enabled", true),
            historyEnabled = yaml.getBoolean("history.enabled", false),
            historyKeepDays = yaml.getInt("history.keep-days", 14).coerceIn(0, 3650),
            historyEntries = yaml.getInt("history.entries", 15).coerceIn(1, 100),
            maxLength = yaml.getInt("max-length.chars", 0).coerceAtLeast(0),
            cooldownAffectsPm = yaml.getBoolean("cooldown-affect-pm", false),
            repeatAffectsPm = yaml.getBoolean("anti-repeat.affect-pm", false),
            floodAffectsPm = yaml.getBoolean("anti-flood.affect-pm", true),
            filterAffectsPm = yaml.getBoolean("filter.affect-pm", true),
            linksAffectsPm = yaml.getBoolean("links.affect-pm", true),
            historyAffectsPm = yaml.getBoolean("history.affect-pm", false),
            maxLengthAffectsPm = yaml.getBoolean("max-length.affect-pm", true),
        )
    }

    private fun loadBlockedWords(yaml: YamlConfiguration): List<String> {
        val words = LinkedHashSet<String>()
        yaml.getStringList("filter.words").forEach { raw ->
            raw.trim().lowercase().ifEmpty { null }?.let(words::add)
        }
        val fileName = yaml.getString("filter.words-file")?.trim().orEmpty()
        if (fileName.isNotEmpty()) {
            val wordsFile = file(fileName)
            if (wordsFile.isFile) {
                wordsFile.readLines(Charsets.UTF_8).forEach { line ->
                    val word = line.trim().lowercase()
                    if (word.isNotEmpty() && !word.startsWith("#")) words += word
                }
            } else {
                plugin.logger.warning("modules/moderation.yml: 'filter.words-file' $fileName does not exist; ignoring it.")
            }
        }
        return words.toList()
    }

    private fun parseMentions(yaml: YamlConfiguration): MentionsConfig {
        val cooldown = yaml.getString("cooldown")?.trim().orEmpty().let { raw ->
            if (raw.isEmpty()) 0L else Durations.parseMillis(raw) ?: run {
                plugin.logger.warning("modules/mentions.yml: invalid 'cooldown' value '$raw'; ignoring it.")
                0L
            }
        }
        return MentionsConfig(
            enabled = yaml.getBoolean("enabled", true),
            highlight = yaml.getString("highlight") ?: "<yellow>@<player></yellow>",
            cooldownMillis = cooldown,
            sound = parseSound("modules/mentions.yml", yaml.getConfigurationSection("sound")),
        )
    }

    private fun parsePrivateMessages(yaml: YamlConfiguration): PrivateMessagesConfig = PrivateMessagesConfig(
        enabled = yaml.getBoolean("enabled", true),
        senderFormat = yaml.getString("sender-format")
            ?: "<gray>[<gold>You</gold> → <gold><target></gold>]</gray> <white><message></white>",
        receiverFormat = yaml.getString("receiver-format")
            ?: "<gray>[<gold><player></gold> → <gold>You</gold>]</gray> <white><message></white>",
        spyFormat = yaml.getString("spy-format")
            ?: "<dark_gray>[Spy] <gray><player> → <target>:</gray> <message>",
        notifyMonitored = yaml.getBoolean("notify-monitored", false),
        respectMutes = yaml.getBoolean("respect-mutes", true),
        sound = parseSound("modules/private-messages.yml", yaml.getConfigurationSection("sound")),
    )

    private fun parseTags(yaml: YamlConfiguration): TagsConfig {
        val reparser = REPARSING_PLUGINS.firstOrNull { plugin.server.pluginManager.getPlugin(it) != null }
        if (reparser != null) {
            plugin.logger.info(
                "modules/minimessage-tags.yml: $reparser reads chat text again after Voxen, so tags a player " +
                    "may not use are handed to it escaped. Tags it renders that are missing from 'custom-tags' " +
                    "stay outside that protection."
            )
        }
        return TagsConfig(
            mode = TagsConfig.UnauthorizedMode.from(yaml.getString("unauthorized-mode")),
            legacyEnabled = yaml.getBoolean("legacy.enabled", true),
            rules = parseTagSection(yaml, "tags"),
            custom = parseTagSection(yaml, "custom-tags"),
            replacements = parseTagSection(yaml, "replacements"),
            reparsed = reparser != null,
        )
    }

    private fun parseTagSection(yaml: YamlConfiguration, sectionName: String): Map<String, TagsConfig.TagRule> =
        buildMap {
            val section = yaml.getConfigurationSection(sectionName) ?: return@buildMap
            for (name in section.getKeys(false)) {
                val rule = section.getConfigurationSection(name) ?: continue
                val lower = name.lowercase()
                val blocked = mutableListOf<Pattern>()
                for (raw in rule.getStringList("blocked-params")) {
                    try {
                        blocked += Pattern.compile(raw, Pattern.CASE_INSENSITIVE)
                    } catch (ex: PatternSyntaxException) {
                        plugin.logger.warning("modules/minimessage-tags.yml: invalid regex in '$sectionName.$name.blocked-params' ('$raw'): ${ex.description}; skipping it.")
                    }
                }
                put(
                    lower,
                    TagsConfig.TagRule(
                        name = lower,
                        enabled = rule.getBoolean("enabled", true),
                        permission = rule.getString("permission")?.trim()?.lowercase()?.ifEmpty { null }
                            ?: "voxen.chat.tag.$lower",
                        aliases = rule.getStringList("aliases").map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                        blockedParams = blocked,
                        actionPermissions = buildMap {
                            val actions = rule.getConfigurationSection("actions") ?: return@buildMap
                            for (action in actions.getKeys(false)) {
                                actions.getString(action)?.trim()?.lowercase()?.ifEmpty { null }
                                    ?.let { put(action.lowercase(), it) }
                            }
                        },
                        value = rule.getString("value").orEmpty(),
                        requirePermission = rule.getBoolean("require-permission", true),
                    ),
                )
            }
        }

    private fun parseParty(yaml: YamlConfiguration): PartyConfig {
        val expiry = yaml.getString("invite-expiry")?.trim().orEmpty().let { raw ->
            if (raw.isEmpty()) 60_000L else Durations.parseMillis(raw) ?: run {
                plugin.logger.warning("modules/party.yml: invalid 'invite-expiry' value '$raw'; using 60s.")
                60_000L
            }
        }
        return PartyConfig(
            enabled = yaml.getBoolean("enabled", false),
            maxMembers = yaml.getInt("max-members", 8).coerceAtLeast(2),
            inviteExpiryMillis = expiry,
        )
    }

    private fun parseEmotes(yaml: YamlConfiguration): EmotesConfig {
        val section = yaml.getConfigurationSection("emotes")
        val emotes = buildMap {
            for (key in section?.getKeys(false).orEmpty()) {
                val name = key.trim().lowercase()
                val value = section?.getString(key)?.ifEmpty { null } ?: continue
                if (!name.matches(EMOTE_NAME)) {
                    plugin.logger.warning("modules/emotes.yml: '$key' may only contain [a-z0-9_+-]; skipping it.")
                    continue
                }
                put(name, value)
            }
        }
        return EmotesConfig(
            enabled = yaml.getBoolean("enabled", true),
            requirePermission = yaml.getBoolean("require-permission", true),
            emotes = emotes,
        )
    }

    private fun parseIntegrations(yaml: YamlConfiguration): IntegrationsConfig = IntegrationsConfig(
        placeholderApi = yaml.getBoolean("placeholderapi", true),
        miniPlaceholders = yaml.getBoolean("miniplaceholders", true),
        luckPerms = yaml.getBoolean("luckperms", true),
        vaultChat = yaml.getBoolean("vault-chat", true),
        discordSrv = yaml.getBoolean("discordsrv", true),
        essentialsDiscord = yaml.getBoolean("essentials-discord", true),
        towny = yaml.getBoolean("towny", true),
        factions = yaml.getBoolean("factions", true),
        mcmmo = yaml.getBoolean("mcmmo", true),
    )

    private fun parseNetwork(section: ConfigurationSection?): NetworkConfig = parseNetwork(
        section,
        NetworkConfig.Transport.from(section?.getString("transport")),
    )

    private fun parseNetwork(section: ConfigurationSection?, transport: NetworkConfig.Transport): NetworkConfig = NetworkConfig(
        transport = transport,
        serverId = section?.getString("server-id")?.trim()?.ifEmpty { null } ?: "server-1",
        reconnectSeconds = (section?.getLong("reconnect-seconds", 5L) ?: 5L).coerceAtLeast(1L),
        timeoutMillis = (section?.getLong("timeout-millis", 5000L) ?: 5000L).coerceAtLeast(500L),
        syncMutes = section?.getBoolean("sync-mutes", true) ?: true,
        secret = networkSecret(section, transport),
        allowUnsigned = section?.getBoolean("allow-unsigned", false) ?: false,
        maxAgeSeconds = (section?.getLong("max-age-seconds", 60L) ?: 60L).coerceAtLeast(0L),
        queueSize = (section?.getInt("queue-size", 1000) ?: 1000).coerceAtLeast(1),
        redis = NetworkConfig.Redis(
            host = section?.getString("redis.host") ?: "localhost",
            port = section?.getInt("redis.port", 6379) ?: 6379,
            username = section?.getString("redis.username") ?: "",
            password = section?.getString("redis.password") ?: "",
            ssl = section?.getBoolean("redis.ssl", false) ?: false,
            channel = section?.getString("redis.channel")?.trim()?.ifEmpty { null } ?: "voxen:chat",
        ),
        nats = NetworkConfig.Nats(
            url = section?.getString("nats.url") ?: "nats://localhost:4222",
            username = section?.getString("nats.username") ?: "",
            password = section?.getString("nats.password") ?: "",
            subject = section?.getString("nats.subject")?.trim()?.ifEmpty { null } ?: "voxen.chat",
        ),
        rabbit = NetworkConfig.Rabbit(
            host = section?.getString("rabbitmq.host") ?: "localhost",
            port = section?.getInt("rabbitmq.port", 5672) ?: 5672,
            username = section?.getString("rabbitmq.username") ?: "guest",
            password = section?.getString("rabbitmq.password") ?: "guest",
            virtualHost = section?.getString("rabbitmq.virtual-host") ?: "/",
            exchange = section?.getString("rabbitmq.exchange")?.trim()?.ifEmpty { null } ?: "voxen.chat",
        ),
    )

    private fun networkSecret(section: ConfigurationSection?, transport: NetworkConfig.Transport): String {
        val configured = section?.getString("secret")?.trim().orEmpty()
        if (configured.isNotEmpty()) return configured
        if (transport == NetworkConfig.Transport.NONE) return ""
        if (section?.getBoolean("use-velocity-secret", true) == false) return ""
        val serverDirectory = plugin.dataFolder.absoluteFile.parentFile?.parentFile ?: return ""
        val proxy = ProxySecret.velocity(serverDirectory) ?: return ""
        plugin.logger.info("network.secret is empty, using the Velocity forwarding secret to sign cross-server messages.")
        return Envelope.derive(proxy)
    }

    private fun parsePresence(yaml: YamlConfiguration): PresenceConfig {
        fun millis(key: String, fallback: String): Long {
            val raw = yaml.getString(key)?.trim().orEmpty().ifEmpty { fallback }
            return Durations.parseMillis(raw) ?: run {
                plugin.logger.warning("modules/presence.yml: invalid '$key' value '$raw'; using $fallback.")
                Durations.parseMillis(fallback)!!
            }
        }
        return PresenceConfig(
            enabled = yaml.getBoolean("enabled", true),
            heartbeatMillis = millis("heartbeat", "15s").coerceAtLeast(1000L),
            ttlMillis = millis("ttl", "60s").coerceAtLeast(5000L),
            suggestRemotePlayers = yaml.getBoolean("suggest-remote-players", true),
        )
    }

    private fun parseSystemMessages(yaml: YamlConfiguration): SystemMessagesConfig {
        val events = SystemMessagesConfig.Kind.entries.mapNotNull { kind ->
            val section = yaml.getConfigurationSection(kind.id) ?: return@mapNotNull null
            val delay = section.getString("delay")?.trim().orEmpty().let { raw ->
                if (raw.isEmpty()) 0L else Durations.parseMillis(raw) ?: run {
                    plugin.logger.warning("modules/system-messages.yml: invalid '${kind.id}.delay' value '$raw'; ignoring it.")
                    0L
                }
            }
            kind to SystemMessagesConfig.Event(
                enabled = section.getBoolean("enabled", false),
                format = section.getString("format")?.trim().orEmpty(),
                channel = section.getString("channel")?.trim()?.lowercase()?.ifEmpty { null } ?: "global",
                crossServer = section.getBoolean("cross-server", false),
                discord = section.getBoolean("discord", false),
                delayMillis = delay.coerceIn(0L, 60_000L),
            )
        }.toMap()
        for ((kind, event) in events) {
            if (event.enabled && event.format.isEmpty()) {
                plugin.logger.warning("modules/system-messages.yml: '${kind.id}' is enabled but has no 'format', so it stays quiet.")
            }
        }
        return SystemMessagesConfig(
            enabled = yaml.getBoolean("enabled", true),
            respectVanish = yaml.getBoolean("respect-vanish", true),
            events = events,
        )
    }

    private fun parseHelpop(yaml: YamlConfiguration): HelpopConfig {
        val cooldown = yaml.getString("cooldown")?.trim().orEmpty().let { raw ->
            if (raw.isEmpty()) 0L else Durations.parseMillis(raw) ?: run {
                plugin.logger.warning("modules/helpop.yml: invalid 'cooldown' value '$raw'; using 30s.")
                30_000L
            }
        }
        val raw = yaml.getString("mode")?.trim().orEmpty()
        val mode = HelpopConfig.Mode.from(raw)
        if (raw.isNotEmpty() && !raw.equals(mode.id, ignoreCase = true)) {
            plugin.logger.warning(
                "modules/helpop.yml: unknown 'mode' value '$raw'; using ${HelpopConfig.Mode.BROADCAST.id}.",
            )
        }
        return HelpopConfig(
            enabled = yaml.getBoolean("enabled", false),
            mode = mode,
            cooldownMillis = cooldown,
            maxLength = yaml.getInt("max-length", 256).coerceIn(16, 1024),
            maxOpen = yaml.getInt("max-open", 3).coerceAtLeast(0),
            queueLimit = yaml.getInt("queue-limit", 50).coerceIn(1, 500),
            historyLimit = yaml.getInt("history-limit", 50).coerceIn(1, 500),
            keepDays = yaml.getInt("keep-days", 30).coerceAtLeast(0),
            dialogs = yaml.getBoolean("interfaces.dialog", true),
            web = yaml.getBoolean("interfaces.web", true),
            notifyStaff = yaml.getBoolean("notify-staff", true),
        )
    }

    private fun parseMail(yaml: YamlConfiguration): MailConfig {
        val cooldown = yaml.getString("cooldown")?.trim().orEmpty().let { raw ->
            if (raw.isEmpty()) 0L else Durations.parseMillis(raw) ?: run {
                plugin.logger.warning("modules/mail.yml: invalid 'cooldown' value '$raw'; ignoring it.")
                0L
            }
        }
        return MailConfig(
            enabled = yaml.getBoolean("enabled", false),
            maxPerPlayer = yaml.getInt("max-per-player", 30).coerceAtLeast(1),
            expireDays = yaml.getInt("expire-days", 30).coerceAtLeast(0),
            notifyOnJoin = yaml.getBoolean("notify-on-join", true),
            allowWhenOnline = yaml.getBoolean("allow-when-online", false),
            cooldownMillis = cooldown,
        )
    }

    private fun parseModeratorTools(yaml: YamlConfiguration): ModeratorToolsConfig {
        return ModeratorToolsConfig(
            enabled = yaml.getBoolean("enabled", true),
            dialogs = yaml.getBoolean("dialogs", true),
            warningsEnabled = yaml.getBoolean("warnings.enabled", true),
            warningExpireMillis = warningExpiry(yaml),
            notifyTarget = yaml.getBoolean("warnings.notify-target", true),
            warningRules = warningRules(yaml),
            notesEnabled = yaml.getBoolean("notes.enabled", true),
            joinAlerts = yaml.getBoolean("join-alerts", true),
            deleteEnabled = yaml.getBoolean("message-delete.enabled", true),
            deleteKeep = yaml.getInt("message-delete.keep", 200).coerceIn(1, 5000),
            deleteButton = yaml.getBoolean("message-delete.button", true),
            manageButton = yaml.getBoolean("manage-button", true),
            dialogButtons = dialogButtons(yaml),
        )
    }

    private fun parseReports(yaml: YamlConfiguration): ReportsConfig {
        val cooldown = yaml.getString("cooldown")?.trim().orEmpty().let { raw ->
            if (raw.isEmpty()) 0L else Durations.parseMillis(raw) ?: run {
                plugin.logger.warning("modules/reports.yml: invalid 'cooldown' value '$raw'; ignoring it.")
                0L
            }
        }
        val reasons = yaml.getMapList("reasons").mapNotNull { entry ->
            val id = entry["id"]?.toString()?.trim()?.lowercase().orEmpty()
            val label = entry["label"]?.toString()?.trim().orEmpty()
            if (id.isEmpty() || label.isEmpty()) {
                plugin.logger.warning("modules/reports.yml: every 'reasons' entry needs an 'id' and a 'label'; skipping one.")
                return@mapNotNull null
            }
            ReportsConfig.Reason(
                id = id,
                label = label,
                permission = entry["permission"]?.toString()?.trim()?.ifEmpty { null },
            )
        }
        val freeText = yaml.getBoolean("free-text", true)
        if (!freeText && reasons.isEmpty()) {
            plugin.logger.warning("modules/reports.yml: 'free-text' is off and no 'reasons' are configured, so nobody can report.")
        }
        val minLength = yaml.getInt("min-length", 3).coerceAtLeast(1)
        val maxLength = yaml.getInt("max-length", 200).coerceAtLeast(minLength)
        return ReportsConfig(
            enabled = yaml.getBoolean("enabled", false),
            dialogs = yaml.getBoolean("interfaces.dialog", true),
            web = yaml.getBoolean("interfaces.web", true),
            chatButton = yaml.getBoolean("chat-button.enabled", true),
            chatButtonStaffOnly = yaml.getBoolean("chat-button.staff-only", false),
            messageKeep = yaml.getInt("chat-button.keep", 200).coerceIn(1, 5000),
            context = yaml.getInt("context", 3).coerceIn(0, 20),
            muteDurations = yaml.getStringList("mute-durations")
                .mapNotNull { raw -> raw.trim().ifEmpty { null }?.takeIf { Durations.parseMillis(it) != null } }
                .ifEmpty { listOf("10m", "1h", "1d") },
            auditLimit = yaml.getInt("audit-limit", 100).coerceIn(1, 1000),
            reasons = reasons,
            freeText = freeText,
            minLength = minLength,
            maxLength = maxLength,
            cooldownMillis = cooldown,
            allowSelf = yaml.getBoolean("allow-self", false),
            allowOffline = yaml.getBoolean("allow-offline", true),
            allowDuplicates = yaml.getBoolean("allow-duplicates", false),
            notifyStaff = yaml.getBoolean("notify-staff", true),
            notifyReporter = yaml.getBoolean("notify-reporter", true),
            queueLimit = yaml.getInt("queue-limit", 100).coerceIn(1, 1000),
            expireDays = yaml.getInt("expire-days", 30).coerceAtLeast(0),
            actions = reportActions(yaml),
        )
    }

    private fun reportActions(yaml: YamlConfiguration): List<ReportsConfig.Action> =
        yaml.getMapList("actions").mapNotNull { entry ->
            val label = entry["label"]?.toString()?.trim().orEmpty()
            val command = entry["command"]?.toString()?.trim()?.removePrefix("/").orEmpty()
            if (label.isEmpty() || command.isEmpty()) {
                plugin.logger.warning("modules/reports.yml: every 'actions' entry needs a 'label' and a 'command'; skipping one.")
                return@mapNotNull null
            }
            val permission = entry["permission"]?.toString()?.trim()?.ifEmpty { null }
            val console = entry["console"] == true
            if (console && permission == null) {
                plugin.logger.warning("modules/reports.yml: action '$label' runs from the console, so it also needs a 'permission'; skipping it.")
                return@mapNotNull null
            }
            ReportsConfig.Action(
                label = label,
                command = command,
                permission = permission,
                console = console,
                resolve = entry["resolve"] == true,
            )
        }

    private fun parseWeb(yaml: YamlConfiguration): WebConfig {
        val users = yaml.getMapList("users").mapNotNull { entry ->
            val name = entry["name"]?.toString()?.trim().orEmpty()
            val password = entry["password"]?.toString().orEmpty()
            if (name.isEmpty()) {
                plugin.logger.warning("modules/web.yml: every 'users' entry needs a 'name'; skipping one.")
                return@mapNotNull null
            }
            @Suppress("UNCHECKED_CAST")
            val permissions = (entry["permissions"] as? List<Any?>).orEmpty()
                .mapNotNull { it?.toString()?.trim()?.lowercase()?.ifEmpty { null } }
                .toSet()
            if (permissions.isEmpty()) {
                plugin.logger.warning("modules/web.yml: user '$name' has no permissions, so every page stays hidden.")
            }
            WebConfig.User(name = name, password = password, permissions = permissions)
        }
        val labels = yaml.getConfigurationSection("labels")?.getValues(true)
            ?.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            ?.toMap()
            .orEmpty()
        val lockout = yaml.getString("lockout")?.trim().orEmpty().let { raw ->
            if (raw.isEmpty()) 0L else Durations.parseMillis(raw) ?: run {
                plugin.logger.warning("modules/web.yml: invalid 'lockout' value '$raw'; using 15m.")
                900_000L
            }
        }
        val refresh = yaml.getString("auto-refresh")?.trim().orEmpty().let { raw ->
            if (raw.isEmpty()) 0L else Durations.parseMillis(raw) ?: run {
                plugin.logger.warning("modules/web.yml: invalid 'auto-refresh' value '$raw'; using 5s.")
                5_000L
            }
        }
        return WebConfig(
            enabled = yaml.getBoolean("enabled", false),
            host = yaml.getString("host")?.trim()?.ifEmpty { null } ?: "127.0.0.1",
            port = yaml.getInt("port", 8085).coerceIn(1, 65535),
            title = yaml.getString("title")?.trim()?.ifEmpty { null } ?: "Voxen",
            realm = yaml.getString("realm")?.trim()?.ifEmpty { null } ?: "Voxen",
            threads = yaml.getInt("threads", 2).coerceIn(1, 32),
            maxLoginAttempts = yaml.getInt("max-login-attempts", 10).coerceAtLeast(0),
            lockoutMillis = lockout,
            refreshSeconds = (refresh / 1000L).coerceIn(0L, 3600L).toInt(),
            users = users,
            labels = labels,
        )
    }

    private fun parseAiModeration(yaml: YamlConfiguration): AiModerationConfig {
        val endpoint = yaml.getString("endpoint", "")!!.trim()
        val enabled = yaml.getBoolean("enabled", false)
        if (enabled && endpoint.isEmpty()) {
            plugin.logger.warning("modules/ai-moderation.yml: 'enabled' is on but 'endpoint' is empty, so nothing will be checked.")
        }
        val headers = yaml.getConfigurationSection("headers")?.getValues(false)
            ?.mapNotNull { (key, value) -> value?.toString()?.let { key to it } }
            ?.toMap()
            .orEmpty()
        return AiModerationConfig(
            enabled = enabled && endpoint.isNotEmpty(),
            endpoint = endpoint,
            headers = headers,
            model = yaml.getString("model", "")!!.trim(),
            label = yaml.getString("label", "unsafe")!!.trim().ifEmpty { "unsafe" },
            requestBody = yaml.getString("request-body", "")!!.trim()
                .ifEmpty { """{"text": {text}, "model": {model}, "labels": ["safe", {label}]}""" },
            scorePath = yaml.getString("score-path", "")!!.trim(),
            timeoutMillis = yaml.getLong("timeout-millis", 1500L).coerceIn(100L, 30_000L),
            queueSize = yaml.getInt("queue-size", 500).coerceIn(1, 100_000),
            minLength = yaml.getInt("min-length", 3).coerceAtLeast(0),
            rules = aiRules(yaml),
        )
    }

    private fun aiRules(yaml: YamlConfiguration): List<AiModerationConfig.Rule> =
        yaml.getMapList("rules").mapNotNull { entry ->
            val score = (entry["score"] as? Number)?.toDouble()
            if (score == null) {
                plugin.logger.warning("modules/ai-moderation.yml: every 'rules' entry needs a numeric 'score'; skipping one.")
                return@mapNotNull null
            }
            @Suppress("UNCHECKED_CAST")
            val actions = (entry["actions"] as? List<Any?>).orEmpty().mapNotNull { raw ->
                AiModerationConfig.Action.from(raw?.toString().orEmpty()).also {
                    if (it == null) plugin.logger.warning("modules/ai-moderation.yml: unknown action '$raw'; ignoring it.")
                }
            }
            val commands = (entry["commands"] as? List<Any?>).orEmpty()
                .mapNotNull { it?.toString()?.trim()?.removePrefix("/")?.ifEmpty { null } }
            if (actions.isEmpty() && commands.isEmpty()) {
                plugin.logger.warning("modules/ai-moderation.yml: the rule at score $score does nothing; skipping it.")
                return@mapNotNull null
            }
            AiModerationConfig.Rule(
                score = if (score > 1.0) score / 100.0 else score,
                actions = actions.toSet(),
                commands = commands,
            )
        }.sortedByDescending { it.score }

    private fun dialogButtons(yaml: YamlConfiguration): List<ModeratorToolsConfig.DialogButton> =
        yaml.getMapList("dialog-buttons").mapNotNull { entry ->
            val label = entry["label"]?.toString()?.trim().orEmpty()
            val command = entry["command"]?.toString()?.trim()?.removePrefix("/").orEmpty()
            if (label.isEmpty() || command.isEmpty()) {
                plugin.logger.warning("modules/moderator-tools.yml: every 'dialog-buttons' entry needs a 'label' and a 'command'; skipping one.")
                return@mapNotNull null
            }
            val permission = entry["permission"]?.toString()?.trim()?.ifEmpty { null }
            val console = entry["console"] as? Boolean ?: false
            if (console && permission == null) {
                plugin.logger.warning(
                    "modules/moderator-tools.yml: the 'dialog-buttons' entry '$label' runs from the console, " +
                        "so it needs a 'permission'; skipping it."
                )
                return@mapNotNull null
            }
            ModeratorToolsConfig.DialogButton(
                label = label,
                command = command,
                permission = permission,
                console = console,
            )
        }

    private fun warningExpiry(yaml: YamlConfiguration): Long {
        val raw = yaml.getString("warnings.expire")?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("never", ignoreCase = true) || raw == "0") {
            val days = yaml.getInt("warnings.expire-days", 0).coerceAtLeast(0)
            return days * 86_400_000L
        }
        return Durations.parseMillis(raw) ?: run {
            plugin.logger.warning("modules/moderator-tools.yml: invalid 'warnings.expire' value '$raw'; warnings never expire.")
            0L
        }
    }

    private fun warningRules(yaml: YamlConfiguration): List<ModeratorToolsConfig.WarningRule> {
        val list = yaml.getMapList("warnings.rules")
        return list.mapNotNull { entry ->
            val at = (entry["at"] as? Number)?.toInt() ?: return@mapNotNull null
            val commands = (entry["commands"] as? List<*>)
                ?.mapNotNull { it?.toString()?.trim()?.ifEmpty { null } }
                .orEmpty()
            if (at <= 0 || commands.isEmpty()) {
                plugin.logger.warning("modules/moderator-tools.yml: skipping a warnings rule without a positive 'at' and at least one command.")
                return@mapNotNull null
            }
            ModeratorToolsConfig.WarningRule(
                at = at,
                repeat = entry["repeat"] as? Boolean ?: false,
                commands = commands,
            )
        }
    }

    private fun parseStorage(yaml: YamlConfiguration): StorageConfig = StorageConfig(
        type = StorageType.from(yaml.getString("type")),
        host = yaml.getString("host") ?: "localhost",
        port = yaml.getInt("port", 3306),
        database = yaml.getString("database") ?: "voxen",
        username = yaml.getString("username") ?: "",
        password = yaml.getString("password") ?: "",
        tablePrefix = yaml.getString("table-prefix") ?: "voxen_",
        poolSize = yaml.getInt("pool-size", 10).coerceAtLeast(1),
        queueSize = yaml.getInt("queue-size", 500).coerceAtLeast(1),
        chatLogBatch = yaml.getInt("chat-log-batch", 100).coerceAtLeast(1),
        sqliteFallback = yaml.getBoolean("sqlite-fallback", false),
    )

    private fun parseItemShare(yaml: YamlConfiguration): ItemShareConfig {
        val cooldown = yaml.getString("item-share.cooldown")?.trim().orEmpty().let { raw ->
            if (raw.isEmpty()) 0L else Durations.parseMillis(raw) ?: run {
                plugin.logger.warning("config.yml: invalid 'item-share.cooldown' value '$raw'; ignoring it.")
                0L
            }
        }
        return ItemShareConfig(
            enabled = yaml.getBoolean("item-share.enabled", true),
            cooldownMillis = cooldown,
        )
    }

    private fun parseCommands(section: ConfigurationSection?): CommandNames = CommandNames(
        message = names(section, "message", listOf("msg", "tell", "whisper", "w")),
        reply = names(section, "reply", listOf("reply", "r")),
        channel = names(section, "channel", listOf("channel", "ch")),
        ignore = names(section, "ignore", listOf("ignore")),
        ignoreList = names(section, "ignore-list", listOf("ignorelist")),
        party = names(section, "party", listOf("party")),
        chatToggle = names(section, "chat-toggle", listOf("chattoggle")),
        language = names(section, "language", listOf("language", "lang")),
        filter = names(section, "filter", listOf("filter")),
        nickname = names(section, "nickname", listOf("nick", "nickname")),
        realName = names(section, "real-name", listOf("realname")),
        mail = names(section, "mail", listOf("mail")),
        helpop = names(section, "helpop", listOf("helpop")),
        report = names(section, "report", listOf("report")),
    )

    private fun names(section: ConfigurationSection?, key: String, defaults: List<String>): List<String> {
        val configured = section?.getStringList(key)
            ?.mapNotNull { it.removePrefix("/").trim().lowercase().ifEmpty { null } }
            .orEmpty()
        return configured.ifEmpty { defaults }
    }

    private fun parseSound(fileName: String, section: ConfigurationSection?): SoundConfig {
        if (section == null || !section.getBoolean("enabled", true)) return SoundConfig(null)
        val rawKey = section.getString("key")?.trim().orEmpty()
        if (rawKey.isEmpty()) return SoundConfig(null)
        val key = runCatching { Key.key(rawKey) }.getOrElse {
            plugin.logger.warning("$fileName: invalid sound key '$rawKey'; disabling this sound.")
            return SoundConfig(null)
        }
        val source = runCatching { Sound.Source.valueOf(section.getString("source", "MASTER")!!.uppercase()) }
            .getOrDefault(Sound.Source.MASTER)
        val volume = section.getDouble("volume", 1.0).toFloat()
        val pitch = section.getDouble("pitch", 1.0).toFloat()
        return SoundConfig(Sound.sound(key, source, volume, pitch))
    }

    private companion object {
        val DEFAULT_FILES = listOf(
            "config.yml",
            "storage.yml",
            "integrations.yml",
            "modules/moderation.yml",
            "modules/mentions.yml",
            "modules/private-messages.yml",
            "modules/minimessage-tags.yml",
            "modules/party.yml",
            "modules/emotes.yml",
            "modules/presence.yml",
            "modules/mail.yml",
            "modules/moderator-tools.yml",
            "modules/reports.yml",
            "modules/web.yml",
            "modules/ai-moderation.yml",
            "modules/system-messages.yml",
            "modules/helpop.yml",
            "messages/en_US.yml",
            "messages/pl_PL.yml",
        )
        val DEFAULT_CHANNELS = listOf("global.yml", "local.yml", "world.yml", "server.yml", "staff.yml", "party.yml")
        val REPARSING_PLUGINS = listOf("Nexo", "Oraxen", "ItemsAdder")
        val VALID_SCOPES = setOf("towny", "factions", "mcmmo")
        val EMOTE_NAME = Regex("[a-z0-9_+-]{1,32}")
        const val CHANNELS_DIR = "channels"
        const val MESSAGES_DIR = "messages"
    }
}
