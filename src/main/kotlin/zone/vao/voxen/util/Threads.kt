package zone.vao.voxen.util

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Every hop between threads goes through here. On Paper anything that is not already on the
 * server thread is queued on the Bukkit scheduler; on Folia player work goes to that player's
 * entity scheduler and only genuinely global state uses the global region scheduler.
 */
class Threads(private val plugin: Plugin) {

    private val folia = runCatching {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
    }.isSuccess

    /** True when the caller may touch plugin-global state and server-wide API right now. */
    fun onGlobal(): Boolean =
        if (folia) plugin.server.isGlobalTickThread else plugin.server.isPrimaryThread

    /** True when the caller may touch this player right now. */
    fun owns(player: Player): Boolean =
        if (folia) plugin.server.isOwnedByCurrentRegion(player) else plugin.server.isPrimaryThread

    /** Global plugin state, config and server-wide API. Never use for a specific player. */
    fun main(block: () -> Unit) {
        if (onGlobal()) {
            block()
            return
        }
        postGlobal(block)
    }

    /** Anything reading or touching one player: packets, inventory, location, permissions. */
    fun forPlayer(player: Player, block: () -> Unit) {
        if (owns(player)) {
            block()
            return
        }
        if (!plugin.isEnabled) return
        if (folia) player.scheduler.run(plugin, { block() }, null) else postGlobal(block)
    }

    /** Runs on the global thread and waits for the result. Never call from a region thread. */
    fun <T> await(block: () -> T): T? = hop(block) { postGlobal(it) }

    /** Runs on the player's own thread and waits for the result. */
    fun <T> awaitPlayer(player: Player, block: () -> T): T? {
        if (owns(player)) return block()
        return hop(block) { task ->
            if (folia) player.scheduler.run(plugin, { task() }, null) else postGlobal(task)
        }
    }

    private fun postGlobal(block: () -> Unit) {
        if (!plugin.isEnabled) return
        if (folia) {
            plugin.server.globalRegionScheduler.run(plugin) { block() }
        } else {
            plugin.server.scheduler.runTask(plugin, Runnable { block() })
        }
    }

    private fun <T> hop(block: () -> T, post: (() -> Unit) -> Unit): T? {
        if (onGlobal()) return block()
        if (!plugin.isEnabled) return null
        val future = CompletableFuture<Result<T>>()
        post { future.complete(runCatching(block)) }
        val result = runCatching { future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrElse {
            plugin.logger.warning("Gave up waiting ${TIMEOUT_SECONDS}s for the server thread; a chat message was dropped.")
            return null
        }
        return result.getOrElse {
            plugin.logger.warning("A chat step failed on the server thread: $it")
            null
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
