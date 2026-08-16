package zone.vao.voxen.hook

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import zone.vao.voxen.config.IntegrationsConfig

class HookManager(private val plugin: JavaPlugin) {

    @Volatile
    var meta: MetaProvider = MetaProvider.None
        private set

    @Volatile
    var papi: PapiFormatHook? = null
        private set

    @Volatile
    var miniPlaceholders: MiniPlaceholdersHook? = null
        private set

    val discord: DiscordHooks = DiscordHooks(plugin.server, plugin.logger)
    val teams: TeamHooks = TeamHooks(plugin.logger)

    @Volatile
    var metaSource: String = "none"
        private set

    fun load(integrations: IntegrationsConfig) {
        meta = resolveMeta(integrations)
        papi = if (integrations.placeholderApi && plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
            runCatching { PapiFormatHook(plugin.logger) }
                .onFailure { plugin.logger.warning("Failed to hook into PlaceholderAPI: ${it.message}") }
                .getOrNull()
        } else {
            null
        }
        miniPlaceholders = if (integrations.miniPlaceholders && plugin.server.pluginManager.isPluginEnabled("MiniPlaceholders")) {
            runCatching { MiniPlaceholdersHook() }
                .onFailure { plugin.logger.warning("Failed to hook into MiniPlaceholders: ${it.message}") }
                .getOrNull()
        } else {
            null
        }
        discord.load(integrations.discordSrv, integrations.essentialsDiscord)
    }

    fun applyPlaceholders(player: Player, text: String): String =
        papi?.apply(player, text) ?: text

    private fun resolveMeta(integrations: IntegrationsConfig): MetaProvider {
        if (integrations.luckPerms && plugin.server.pluginManager.isPluginEnabled("LuckPerms")) {
            val provider = runCatching { LuckPermsMeta() }
                .onFailure { plugin.logger.warning("Failed to hook into LuckPerms: ${it.message}") }
                .getOrNull()
            if (provider != null) {
                metaSource = "LuckPerms"
                return provider
            }
        }
        if (integrations.vaultChat && plugin.server.pluginManager.isPluginEnabled("Vault")) {
            val provider = runCatching { VaultMeta(plugin.server) }
                .onFailure { plugin.logger.warning("Failed to hook into Vault chat: ${it.message}") }
                .getOrNull()
            if (provider != null && provider.available()) {
                metaSource = "Vault"
                return provider
            }
        }
        metaSource = "none"
        return MetaProvider.None
    }
}
