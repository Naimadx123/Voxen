package zone.vao.voxen.util

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

class WorkQueue(
    private val name: String,
    capacity: Int,
    private val logger: Logger,
    private val hint: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val pool = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(capacity.coerceAtLeast(1)),
        { runnable -> Thread(runnable, name).apply { isDaemon = true } },
    )

    private val droppedCount = AtomicLong()
    private val lastWarnAt = AtomicLong(0L)

    fun submit(task: () -> Unit): Boolean =
        try {
            pool.execute(task)
            true
        } catch (ex: RejectedExecutionException) {
            noteDrop()
            false
        }

    fun noteDrop() {
        val total = droppedCount.incrementAndGet()
        val now = clock()
        val last = lastWarnAt.get()
        if (now - last < WARN_INTERVAL_MILLIS || !lastWarnAt.compareAndSet(last, now)) return
        logger.warning("$name is behind and dropped $total task(s) so far. $hint")
    }

    fun dropped(): Long = droppedCount.get()

    fun pending(): Int = pool.queue.size

    fun shutdown(seconds: Long) {
        pool.shutdown()
        runCatching { pool.awaitTermination(seconds, TimeUnit.SECONDS) }
    }

    private companion object {
        const val WARN_INTERVAL_MILLIS = 30_000L
    }
}
