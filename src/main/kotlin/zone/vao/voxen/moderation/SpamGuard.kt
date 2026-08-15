package zone.vao.voxen.moderation

import zone.vao.voxen.config.ModerationConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SpamGuard(
    private val moderation: () -> ModerationConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface Result {
        data object Ok : Result
        data class Cooldown(val remainingMillis: Long) : Result
        data object Repeat : Result
    }

    private data class Recent(val normalized: String, val at: Long)

    private data class State(
        val lastGlobalAt: Long,
        val lastPerChannelAt: Map<String, Long>,
        val recent: List<Recent>,
    )

    private val states = ConcurrentHashMap<UUID, State>()

    fun check(
        uuid: UUID,
        channelId: String,
        channelCooldownMillis: Long,
        content: String,
        bypassCooldown: Boolean,
        bypassRepeat: Boolean,
    ): Result {
        val config = moderation()
        val now = clock()
        val state = states[uuid]

        if (!bypassCooldown && state != null) {
            if (config.cooldownMillis > 0) {
                val remaining = state.lastGlobalAt + config.cooldownMillis - now
                if (remaining > 0) return Result.Cooldown(remaining)
            }
            if (channelCooldownMillis > 0) {
                val lastChannel = state.lastPerChannelAt[channelId] ?: 0L
                val remaining = lastChannel + channelCooldownMillis - now
                if (remaining > 0) return Result.Cooldown(remaining)
            }
        }

        val normalized = normalize(content)
        val recent = state?.recent.orEmpty().filter { inWindow(it, now, config.repeatWindowMillis) }
        if (!bypassRepeat && config.repeatEnabled) {
            if (recent.any { it.normalized == normalized }) return Result.Repeat
            if (config.similarityEnabled && recent.any { similarity(it.normalized, normalized) >= config.similarityThreshold }) {
                return Result.Repeat
            }
        }

        val keep = if (config.repeatEnabled && config.similarityEnabled) config.similarityHistory else 1
        states[uuid] = State(
            lastGlobalAt = now,
            lastPerChannelAt = (state?.lastPerChannelAt.orEmpty()) + (channelId to now),
            recent = (listOf(Recent(normalized, now)) + recent).take(keep),
        )
        return Result.Ok
    }

    fun forget(uuid: UUID) {
        states.remove(uuid)
    }

    fun clear() {
        states.clear()
    }

    private fun normalize(content: String): String =
        content.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun inWindow(entry: Recent, now: Long, windowMillis: Long): Boolean =
        windowMillis <= 0 || now - entry.at <= windowMillis

    private fun similarity(left: String, right: String): Double {
        val longest = maxOf(left.length, right.length)
        if (longest == 0) return 1.0
        return 1.0 - distance(left, right).toDouble() / longest
    }

    private fun distance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val substitution = previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }
}
