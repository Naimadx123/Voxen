package zone.vao.voxen.moderation

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Server
import org.bukkit.entity.Player
import zone.vao.voxen.config.AiModerationConfig
import zone.vao.voxen.config.VoxenConfig
import zone.vao.voxen.util.Threads
import zone.vao.voxen.util.WorkQueue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Logger

class AiModerationService(
    private val server: Server,
    private val config: () -> VoxenConfig,
    private val moderator: ModeratorService,
    private val threads: Threads,
    private val logger: Logger,
    private val classify: (String) -> Double? = DISABLED,
) {

    private val io = WorkQueue("voxen-ai", config().aiModeration.queueSize, logger, "lower ai-moderation queue-size or raise the classifier's throughput")
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }
    private val gson = Gson()
    private val call: (String) -> Double? = if (classify === DISABLED) ::request else classify

    fun inspect(player: Player, content: String) {
        val settings = config().aiModeration
        if (!settings.enabled || settings.rules.isEmpty()) return
        if (content.length < settings.minLength) return
        if (player.hasPermission(BYPASS)) return
        val uuid = player.uniqueId
        val name = player.name
        io.submit {
            val score = runCatching { call(content) }
                .onFailure { logger.warning("AI moderation call failed: ${it.message}") }
                .getOrNull() ?: return@submit
            val rule = settings.ruleFor(score) ?: return@submit
            threads.main { apply(ModeratorService.Target(uuid, name), content, score, rule) }
        }
    }

    fun shutdown() = io.shutdown(2)

    private fun apply(target: ModeratorService.Target, content: String, score: Double, rule: AiModerationConfig.Rule) {
        val messages = config().messages
        val percent = String.format("%.1f", score * 100.0)
        val resolvers = arrayOf(
            Placeholder.unparsed("player", target.name),
            Placeholder.unparsed("score", percent),
            Placeholder.unparsed("message", content),
        )
        if (AiModerationConfig.Action.REPORT in rule.actions) {
            for (staff in server.onlinePlayers) {
                if (!staff.hasPermission(ALERTS)) continue
                threads.forPlayer(staff) { messages.send(staff, "ai-report", *resolvers) }
            }
            logger.info("AI moderation flagged ${target.name} at $percent%: $content")
        }
        if (AiModerationConfig.Action.DELETE in rule.actions) {
            moderator.deleteMessages(server.consoleSender, target, 1)
        }
        if (AiModerationConfig.Action.WARN in rule.actions) {
            moderator.warn(server.consoleSender, target, messages.raw(server.consoleSender, "ai-warn-reason").replace("<score>", percent))
        }
        if (AiModerationConfig.Action.KICK in rule.actions) {
            server.getPlayer(target.uuid)?.let { online ->
                threads.forPlayer(online) { online.kick(messages.line(online, "ai-kick", *resolvers)) }
            }
        }
        for (command in rule.commands) {
            val filled = command
                .replace("<player>", target.name)
                .replace("<uuid>", target.uuid.toString())
                .replace("<score>", percent)
            runCatching { server.dispatchCommand(server.consoleSender, filled) }
                .onFailure { logger.warning("AI moderation command '$filled' failed: ${it.message}") }
        }
    }

    private fun request(text: String): Double? {
        val settings = config().aiModeration
        val body = settings.requestBody
            .replace("{text}", gson.toJson(text))
            .replace("{model}", gson.toJson(settings.model))
            .replace("{label}", gson.toJson(settings.label))
            .let(::dropEmptyMembers)
        val builder = HttpRequest.newBuilder(URI.create(settings.endpoint))
            .timeout(Duration.ofMillis(settings.timeoutMillis))
            .header("Content-Type", "application/json")
        for ((name, value) in settings.headers) builder.header(name, value)
        val response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            logger.warning("AI moderation endpoint answered ${response.statusCode()}: ${response.body().take(300)}")
            return null
        }
        return scoreOf(response.body(), settings.label, settings.scorePath)
    }

    companion object {
        const val BYPASS = "voxen.ai.bypass"
        const val ALERTS = "voxen.mod.alerts"

        fun dropEmptyMembers(body: String): String =
            EMPTY_MEMBER.replace(body, "").replace("{,", "{").replace(",}", "}").replace("{ ,", "{")

        private val EMPTY_MEMBER = Regex("""\s*"[^"]+"\s*:\s*""\s*,?""")

        val DISABLED: (String) -> Double? = { null }

        fun scoreOf(raw: String, label: String, path: String = ""): Double? {
            val root = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return null
            if (path.isEmpty()) return guess(root, label)
            return path.split(',').mapNotNull { one ->
                one.trim().split('.').fold(root as JsonElement?) { node, part -> step(node, part) }
                    ?.let { runCatching { it.asDouble }.getOrNull() }
            }.maxOrNull()
        }

        private fun step(node: JsonElement?, step: String): JsonElement? = when {
            node == null -> null
            step == "*" -> children(node).maxByOrNull { runCatching { it.asDouble }.getOrDefault(Double.NEGATIVE_INFINITY) }
            node.isJsonArray -> step.toIntOrNull()?.let { node.asJsonArray.asList().getOrNull(it) }
            node.isJsonObject -> node.asJsonObject.get(step)
            else -> null
        }

        private fun children(node: JsonElement): List<JsonElement> = when {
            node.isJsonArray -> node.asJsonArray.toList()
            node.isJsonObject -> node.asJsonObject.entrySet().map { it.value }
            else -> emptyList()
        }

        private fun guess(element: JsonElement, label: String): Double? = when {
            element.isJsonArray -> element.asJsonArray.mapNotNull { guess(it, label) }.maxOrNull()
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val named = obj.get(label)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }
                val labelled = obj.get("label")?.takeIf { it.isJsonPrimitive }?.asString
                when {
                    named != null -> named
                    labelled != null ->
                        if (labelled.equals(label, ignoreCase = true)) runCatching { obj.get("score").asDouble }.getOrNull() else null
                    obj.has("score") -> runCatching { obj.get("score").asDouble }.getOrNull()
                    else -> obj.entrySet().mapNotNull { guess(it.value, label) }.maxOrNull()
                }
            }
            else -> null
        }
    }
}
