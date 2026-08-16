package zone.vao.voxen.config

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import zone.vao.voxen.channel.Channel
import zone.vao.voxen.channel.ChannelType
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
        return Channel(
            id = id,
            displayName = section.getString("display-name")?.ifEmpty { null } ?: id,
            type = type,
            enabled = section.getBoolean("enabled", true),
            defaultChannel = section.getBoolean("default", false),
            defaultActive = section.getBoolean("default-active", false),
            readOnly = section.getBoolean("read-only", false),
            crossServer = section.getBoolean("cross-server", type == ChannelType.SERVER),
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
        sound = parseSound("modules/private-messages.yml", yaml.getConfigurationSection("sound")),
    )

    private fun parseTags(yaml: YamlConfiguration): TagsConfig = TagsConfig(
        mode = resolveUnauthorizedMode(yaml),
        legacyEnabled = yaml.getBoolean("legacy.enabled", true),
        rules = parseTagSection(yaml, "tags"),
        custom = parseTagSection(yaml, "custom-tags"),
        replacements = parseTagSection(yaml, "replacements"),
    )

    private fun resolveUnauthorizedMode(yaml: YamlConfiguration): TagsConfig.UnauthorizedMode {
        val configured = TagsConfig.UnauthorizedMode.from(yaml.getString("unauthorized-mode"))
        if (configured != TagsConfig.UnauthorizedMode.ESCAPE) return configured
        val reparser = REPARSING_PLUGINS.firstOrNull { plugin.server.pluginManager.getPlugin(it) != null }
            ?: return configured
        plugin.logger.info(
            "modules/minimessage-tags.yml: $reparser re-parses chat text, so 'unauthorized-mode: escape' " +
                "would let blocked tags render anyway; using 'strip' instead."
        )
        return TagsConfig.UnauthorizedMode.STRIP
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
            enabled = yaml.getBoolean("enabled", true),
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

    private fun parseNetwork(section: ConfigurationSection?): NetworkConfig = NetworkConfig(
        transport = NetworkConfig.Transport.from(section?.getString("transport")),
        serverId = section?.getString("server-id")?.trim()?.ifEmpty { null } ?: "server-1",
        reconnectSeconds = (section?.getLong("reconnect-seconds", 5L) ?: 5L).coerceAtLeast(1L),
        timeoutMillis = (section?.getLong("timeout-millis", 5000L) ?: 5000L).coerceAtLeast(500L),
        syncMutes = section?.getBoolean("sync-mutes", true) ?: true,
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

    private fun parseStorage(yaml: YamlConfiguration): StorageConfig = StorageConfig(
        type = StorageType.from(yaml.getString("type")),
        host = yaml.getString("host") ?: "localhost",
        port = yaml.getInt("port", 3306),
        database = yaml.getString("database") ?: "voxen",
        username = yaml.getString("username") ?: "",
        password = yaml.getString("password") ?: "",
        tablePrefix = yaml.getString("table-prefix") ?: "voxen_",
        poolSize = yaml.getInt("pool-size", 10).coerceAtLeast(1),
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
            "messages/en_US.yml",
            "messages/pl_PL.yml",
        )
        val DEFAULT_CHANNELS = listOf("global.yml", "local.yml", "world.yml", "server.yml", "staff.yml", "party.yml")
        val REPARSING_PLUGINS = listOf("Nexo", "Oraxen")
        val VALID_SCOPES = setOf("towny", "factions", "mcmmo")
        val EMOTE_NAME = Regex("[a-z0-9_+-]{1,32}")
        const val CHANNELS_DIR = "channels"
        const val MESSAGES_DIR = "messages"
    }
}
