package zone.vao.voxen.util

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class Threads(private val plugin: Plugin) {

    private val folia = runCatching {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
    }.isSuccess

    fun main(block: () -> Unit) {
        if (plugin.server.isPrimaryThread) {
            block()
            return
        }
        if (!plugin.isEnabled) return
        plugin.server.globalRegionScheduler.run(plugin) { block() }
    }

    fun forPlayer(player: Player, block: () -> Unit) {
        if (!folia || plugin.server.isPrimaryThread) {
            block()
            return
        }
        if (!plugin.isEnabled) return
        player.scheduler.run(plugin, { block() }, null)
    }

    fun <T> await(block: () -> T): T? {
        if (plugin.server.isPrimaryThread) return block()
        if (!plugin.isEnabled) return null
        val future = CompletableFuture<Result<T>>()
        plugin.server.globalRegionScheduler.run(plugin) { future.complete(runCatching(block)) }
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
