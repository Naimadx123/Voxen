package zone.vao.voxen.web

import com.sun.net.httpserver.BasicAuthenticator
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import zone.vao.voxen.config.WebConfig
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger

class WebServer(
    private val logger: Logger,
    private val config: () -> WebConfig,
) {

    private val modules = LinkedHashMap<String, WebModule>()
    private val tokens = ConcurrentHashMap<String, String>()
    private val failures = ConcurrentHashMap<String, Failures>()
    private val random = SecureRandom()

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var bound: String? = null

    fun register(module: WebModule) {
        modules[module.id] = module
    }

    fun running(): Boolean = server != null

    fun address(): String = bound ?: "none"

    fun start() {
        stop()
        val settings = config()
        if (!settings.enabled || modules.isEmpty()) return
        if (settings.users.isEmpty()) {
            logger.warning("modules/web.yml: 'enabled' is on but no users are configured, so the panel stays off.")
            return
        }
        val unusable = settings.users.filter { weak(it.password) }
        if (unusable.isNotEmpty()) {
            logger.severe(
                "modules/web.yml: user(s) ${unusable.joinToString(", ") { it.name }} have no password, still use the " +
                    "default one, or one shorter than $MIN_PASSWORD_LENGTH characters. Set a real password; the panel " +
                    "stays off until then.",
            )
            return
        }
        val duplicates = settings.users.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            logger.severe(
                "modules/web.yml: user name(s) ${duplicates.joinToString(", ")} are defined more than once, so it is " +
                    "unclear which password and permissions apply. The panel stays off until that is fixed.",
            )
            return
        }
        if (settings.host == "0.0.0.0") {
            logger.warning(
                "modules/web.yml: the panel is bound to every interface over plain HTTP. Put it behind a reverse proxy " +
                    "with TLS, or bind it to 127.0.0.1 and reach it through an SSH tunnel.",
            )
        }
        val created = runCatching { HttpServer.create(InetSocketAddress(settings.host, settings.port), 0) }.getOrElse {
            logger.warning("Failed to start the web panel on ${settings.host}:${settings.port}: ${it.message}")
            return
        }
        created.createContext("/") { exchange -> handle(exchange) }.authenticator = Login(settings.realm)
        created.executor = Executors.newFixedThreadPool(settings.threads) { task ->
            Thread(task, "voxen-web").apply { isDaemon = true }
        }
        runCatching { created.start() }.onFailure {
            logger.warning("Failed to start the web panel on ${settings.host}:${settings.port}: ${it.message}")
            return
        }
        server = created
        bound = "${settings.host}:${settings.port}"
        logger.info("Voxen web panel is listening on http://$bound/")
    }

    fun stop() {
        val current = server ?: return
        val previous = bound
        server = null
        bound = null
        tokens.clear()
        failures.clear()
        runCatching { current.stop(0) }
        (current.executor as? ExecutorService)?.shutdownNow()
        logger.info("Voxen web panel on $previous stopped.")
    }

    private fun handle(exchange: HttpExchange) {
        try {
            route(exchange)
        } catch (ex: Exception) {
            logger.warning("Web panel request ${safe(exchange.requestURI.path)} failed: ${ex.message}")
            respond(exchange, 500, notice("Something went wrong, see the server log."))
        } finally {
            runCatching { exchange.close() }
        }
    }

    private fun route(exchange: HttpExchange) {
        val user = config().user(exchange.principal?.username.orEmpty()) ?: run {
            respond(exchange, 403, notice("Unknown user."))
            return
        }
        if (exchange.requestMethod !in METHODS) {
            exchange.responseHeaders.add("Allow", METHODS.joinToString(", "))
            respond(exchange, 405, notice("That request method is not supported."))
            return
        }
        val segment = exchange.requestURI.path.trim('/').substringBefore('/')
        if (segment.isEmpty()) {
            respond(exchange, 200, index(user))
            return
        }
        val module = modules[segment]?.takeIf { it.enabled() } ?: run {
            respond(exchange, 404, shell(user, null, notice("Page not found.")))
            return
        }
        if (!user.allows(module.permission)) {
            respond(exchange, 403, shell(user, null, notice("You are not allowed to see this page.")))
            return
        }
        val query = parse(exchange.requestURI.rawQuery)
        val token = tokens.computeIfAbsent(user.name) {
            HexFormat.of().formatHex(ByteArray(TOKEN_BYTES).also(random::nextBytes))
        }
        if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
            val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0L
            if (declared > MAX_BODY_BYTES) {
                respond(exchange, 413, shell(user, module.id, notice("That form was too large.")))
                return
            }
            val form = parse(String(exchange.requestBody.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8))
            if (!MessageDigest.isEqual(token.toByteArray(), form["token"].orEmpty().toByteArray())) {
                respond(exchange, 403, shell(user, module.id, notice("This form expired, reload the page.")))
                return
            }
            runCatching { module.submit(WebRequest(module.id, user, query, form, token)) }
                .onFailure { logger.warning("Web panel action on '${module.id}' failed: ${it.message}") }
            exchange.responseHeaders.add("Location", redirect(segment, query))
            respond(exchange, 303, "")
            return
        }
        val body = runCatching { module.render(WebRequest(module.id, user, query, emptyMap(), token)) }.getOrElse {
            logger.warning("Web panel page '${module.id}' failed to render: ${it.message}")
            notice("This page could not be rendered, see the server log.")
        }
        respond(exchange, 200, shell(user, module.id, body))
    }

    private fun index(user: WebConfig.User): String {
        val visible = modules.values.filter { it.enabled() && user.allows(it.permission) }
        val body = if (visible.isEmpty()) {
            notice("No page is available for this account.")
        } else {
            visible.joinToString("", prefix = "<div class=\"cards\">", postfix = "</div>") { module ->
                "<a href=\"/${module.id}\">${Html.escape(module.title())}</a>"
            }
        }
        return shell(user, null, body)
    }

    private fun shell(user: WebConfig.User, active: String?, body: String): String {
        val nav = modules.values
            .filter { it.enabled() && user.allows(it.permission) }
            .joinToString("") { module ->
                val title = module.title()
                val current = if (module.id == active) " class=\"active\"" else ""
                "<a href=\"/${module.id}\"$current><span class=\"ico\">${Html.badge(title)}</span>" +
                    "<span>${Html.escape(title)}</span></a>"
            }
        return Html.document(config().title, nav, body, user.name)
    }

    private fun redirect(segment: String, query: Map<String, String>): String {
        val path = "/" + Html.encode(segment)
        if (query.isEmpty()) return path
        return path + query.entries.joinToString("&", prefix = "?") { (key, value) ->
            "${Html.encode(key)}=${Html.encode(value)}"
        }
    }

    private fun weak(password: String): Boolean =
        password.length < MIN_PASSWORD_LENGTH || password == DEFAULT_PASSWORD

    private fun notice(message: String): String =
        "<div class=\"card\"><p class=\"empty\">${Html.escape(message)}</p></div>"

    private fun respond(exchange: HttpExchange, status: Int, html: String) {
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.add(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
        )
        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.add("X-Frame-Options", "DENY")
        exchange.responseHeaders.add("Referrer-Policy", "no-referrer")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        runCatching {
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun parse(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return buildMap {
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                runCatching {
                    put(
                        URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substringAfter('=', ""), StandardCharsets.UTF_8),
                    )
                }
            }
        }
    }

    private inner class Login(realm: String) : BasicAuthenticator(realm) {

        override fun authenticate(exchange: HttpExchange): Result {
            val address = exchange.remoteAddress?.address?.hostAddress ?: "unknown"
            val settings = config()
            if (locked(address, settings)) return Failure(429)
            val offered = exchange.requestHeaders.getFirst("Authorization") != null
            val result = super.authenticate(exchange)
            if (result is Success) failures.remove(address) else if (offered) note(address, settings)
            return result
        }

        override fun checkCredentials(username: String, password: String): Boolean {
            val offered = password.toByteArray(StandardCharsets.UTF_8)
            val user = config().user(username) ?: run {
                MessageDigest.isEqual(DUMMY_PASSWORD, offered)
                return false
            }
            if (weak(user.password)) return false
            return MessageDigest.isEqual(user.password.toByteArray(StandardCharsets.UTF_8), offered)
        }
    }

    private class Failures(var count: Int, var since: Long)

    private fun locked(address: String, settings: WebConfig): Boolean {
        if (settings.maxLoginAttempts <= 0 || settings.lockoutMillis <= 0L) return false
        val entry = failures[address] ?: return false
        synchronized(entry) {
            if (System.currentTimeMillis() - entry.since >= settings.lockoutMillis) {
                failures.remove(address)
                return false
            }
            return entry.count >= settings.maxLoginAttempts
        }
    }

    private fun note(address: String, settings: WebConfig) {
        if (settings.maxLoginAttempts <= 0 || settings.lockoutMillis <= 0L) return
        val entry = failures.computeIfAbsent(address) { Failures(0, System.currentTimeMillis()) }
        val reached = synchronized(entry) {
            val now = System.currentTimeMillis()
            if (now - entry.since >= settings.lockoutMillis) {
                entry.count = 0
                entry.since = now
            }
            entry.count++
            entry.count == settings.maxLoginAttempts
        }
        if (reached) {
            logger.warning("Web panel: $address failed to log in ${settings.maxLoginAttempts} times and is locked out.")
        }
    }

    private fun safe(value: String): String = value.filter { it.code in 32..126 }.take(200)

    companion object {
        const val DEFAULT_PASSWORD = "change-me"

        private const val MAX_BODY_BYTES = 64 * 1024
        private const val TOKEN_BYTES = 24
        private const val MIN_PASSWORD_LENGTH = 8

        private val METHODS = setOf("GET", "HEAD", "POST")

        private val DUMMY_PASSWORD = "voxen-unknown-account".toByteArray(StandardCharsets.UTF_8)
    }
}
