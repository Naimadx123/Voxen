package zone.vao.voxen.hook

import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.UUID
import java.util.logging.Logger

interface DiscordForwarder : AutoCloseable {
    fun forward(player: Player, message: String, channel: String?, preformatted: Boolean)
}

interface DiscordReceiver {
    fun claims(channelNames: Set<String>): Boolean
    fun receive(message: DiscordMessage)
}

class DiscordMessage(
    val id: String,
    val channelId: String,
    val channelName: String,
    val channelNames: Set<String>,
    val authorId: String,
    val authorName: String,
    val username: String,
    val roles: List<String>,
    val roleColor: Int?,
    val linked: UUID?,
    val replyTo: String?,
    val content: String,
    val attachments: List<Attachment>,
    val markdown: (String) -> Component? = { null },
    val reject: () -> Unit = {},
) {

    data class Attachment(val name: String, val url: String)
}

class DiscordHooks(
    private val server: Server,
    private val logger: Logger,
) {

    @Volatile
    private var discordSrvAvailable = false

    @Volatile
    private var essentialsAvailable = false

    @Volatile
    var receiver: DiscordReceiver? = null

    private val inbound = object : DiscordReceiver {
        override fun claims(channelNames: Set<String>): Boolean = receiver?.claims(channelNames) == true

        override fun receive(message: DiscordMessage) {
            receiver?.receive(message)
        }
    }

    private var discordSrvBridge: DiscordForwarder? = null
    private var essentialsService: Any? = null
    private var essentialsSend: Method? = null
    private var essentialsChatType: Any? = null

    fun load(discordSrvEnabled: Boolean, essentialsEnabled: Boolean) {
        close()
        if (discordSrvEnabled && server.pluginManager.isPluginEnabled("DiscordSRV")) {
            runCatching {
                discordSrvBridge = Class.forName("zone.vao.voxen.hook.DiscordSrvBridge")
                    .getConstructor(Logger::class.java, DiscordReceiver::class.java)
                    .newInstance(logger, inbound) as DiscordForwarder
                discordSrvAvailable = true
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

    fun forward(player: Player, plainMessage: String, preformatted: Boolean = false, channel: String? = null) {
        if (discordSrvAvailable) {
            runCatching {
                discordSrvBridge?.forward(player, plainMessage, channel, preformatted)
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

    fun close() {
        discordSrvAvailable = false
        essentialsAvailable = false
        runCatching { discordSrvBridge?.close() }
            .onFailure { logger.warning("Failed to detach from DiscordSRV: ${it.message}") }
        discordSrvBridge = null
        essentialsService = null
        essentialsSend = null
        essentialsChatType = null
    }
}
