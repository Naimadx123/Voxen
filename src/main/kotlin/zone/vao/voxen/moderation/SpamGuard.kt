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

    private data class State(
        val lastGlobalAt: Long,
        val lastPerChannelAt: Map<String, Long>,
        val lastNormalized: String?,
        val lastNormalizedAt: Long,
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
        if (!bypassRepeat && config.repeatEnabled && state?.lastNormalized == normalized &&
            (config.repeatWindowMillis <= 0 || now - state.lastNormalizedAt <= config.repeatWindowMillis)
        ) {
            return Result.Repeat
        }

        states[uuid] = State(
            lastGlobalAt = now,
            lastPerChannelAt = (state?.lastPerChannelAt.orEmpty()) + (channelId to now),
            lastNormalized = normalized,
            lastNormalizedAt = now,
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
}
