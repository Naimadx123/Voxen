package zone.vao.voxen.hook

import org.bukkit.Server
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.logging.Logger

class DiscordHooks(
    private val server: Server,
    private val logger: Logger,
) {

    @Volatile
    private var discordSrvAvailable = false

    @Volatile
    private var essentialsAvailable = false

    private var processChatMessage: Method? = null
    private var discordSrvInstance: Any? = null
    private var essentialsService: Any? = null
    private var essentialsSend: Method? = null
    private var essentialsChatType: Any? = null

    fun load(discordSrvEnabled: Boolean, essentialsEnabled: Boolean) {
        discordSrvAvailable = false
        essentialsAvailable = false
        if (discordSrvEnabled && server.pluginManager.isPluginEnabled("DiscordSRV")) {
            runCatching {
                val clazz = Class.forName("github.scarsz.discordsrv.DiscordSRV")
                discordSrvInstance = clazz.getMethod("getPlugin").invoke(null)
                processChatMessage = clazz.methods.firstOrNull { method ->
                    method.name == "processChatMessage" && method.parameterCount == 4 &&
                        method.parameterTypes[0] == Player::class.java
                }
                if (processChatMessage != null) discordSrvAvailable = true
            }.onFailure { logger.warning("Failed to hook into DiscordSRV: ${it.message}") }
        }
        if (essentialsEnabled && server.pluginManager.isPluginEnabled("EssentialsDiscord")) {
            runCatching {
                val serviceClass = Class.forName("net.essentialsx.api.v2.services.discord.DiscordService")
                val registration = server.servicesManager.getRegistration(serviceClass) ?: return@runCatching
                essentialsService = registration.provider
                val typeClass = Class.forName("net.essentialsx.api.v2.services.discord.MessageType\$DefaultTypes")
                essentialsChatType = typeClass.getField("CHAT").get(null)
                val messageTypeClass = Class.forName("net.essentialsx.api.v2.services.discord.MessageType")
                essentialsSend = serviceClass.getMethod(
                    "sendMessage",
                    messageTypeClass,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                )
                if (essentialsService != null && essentialsChatType != null) essentialsAvailable = true
            }.onFailure { logger.warning("Failed to hook into EssentialsDiscord: ${it.message}") }
        }
    }

    fun forward(player: Player, plainMessage: String, preformatted: Boolean = false) {
        if (discordSrvAvailable) {
            runCatching {
                processChatMessage?.invoke(discordSrvInstance, player, plainMessage, null, false)
            }.onFailure { logger.warning("Failed to forward a message to DiscordSRV: ${it.message}") }
        }
        if (essentialsAvailable) {
            val text = if (preformatted) plainMessage else "${player.name}: $plainMessage"
            runCatching {
                essentialsSend?.invoke(essentialsService, essentialsChatType, text, true)
            }.onFailure { logger.warning("Failed to forward a message to EssentialsDiscord: ${it.message}") }
        }
    }

    fun discordSrv(): Boolean = discordSrvAvailable

    fun essentials(): Boolean = essentialsAvailable
}
