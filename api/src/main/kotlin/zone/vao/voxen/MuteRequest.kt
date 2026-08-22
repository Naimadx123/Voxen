package zone.vao.voxen

import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One mute, built up field by field. Pass it to [VoxenApi.mute].
 *
 * ```java
 * MuteRequest.builder(uuid, "Steve", "AutoMod")
 *         .channel("global")
 *         .duration(1, TimeUnit.HOURS)
 *         .reason("spam")
 *         .build();
 * ```
 *
 * Left alone it mutes every channel, forever, with no reason given.
 */
class MuteRequest private constructor(
    val target: UUID,
    val targetName: String,
    val channelId: String?,
    val durationMillis: Long,
    val reason: String?,
    val moderator: String,
) {

    /** Collects the optional parts. Get one from [MuteRequest.builder]. */
    class Builder internal constructor(
        private val target: UUID,
        private val targetName: String,
        private val moderator: String,
    ) {

        private var channelId: String? = null
        private var durationMillis = 0L
        private var reason: String? = null

        /** Limits the mute to one channel. Null, the default, covers every channel. */
        fun channel(channelId: String?): Builder = apply { this.channelId = channelId }

        /** How long the mute lasts. Zero or less, the default, makes it permanent. */
        fun duration(millis: Long): Builder = apply { this.durationMillis = millis }

        /** The same in whatever unit reads better. */
        fun duration(amount: Long, unit: TimeUnit): Builder = duration(unit.toMillis(amount))

        /** Makes the mute permanent, undoing an earlier [duration] call. */
        fun permanent(): Builder = duration(0L)

        /** The reason shown to the player and written into the record. */
        fun reason(reason: String?): Builder = apply { this.reason = reason }

        fun build(): MuteRequest =
            MuteRequest(target, targetName, channelId, durationMillis, reason, moderator)
    }

    companion object {
        /**
         * Starts a mute for [target], whose name is [targetName], issued by
         * [moderator]. That name goes into the record, so use something a
         * human will recognise later.
         */
        @JvmStatic
        fun builder(target: UUID, targetName: String, moderator: String): Builder =
            Builder(target, targetName, moderator)
    }
}
