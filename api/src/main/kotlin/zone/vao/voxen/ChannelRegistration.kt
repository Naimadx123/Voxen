package zone.vao.voxen

/**
 * A channel your plugin owns, built up field by field. Pass it to
 * [VoxenApi.registerChannel].
 *
 * ```java
 * ChannelRegistration.builder("auction", "<gold>Auction")
 *         .format("<gold>[A] <player> <dark_gray>» <white><message>")
 *         .recipients(sender -> interestedPlayers())
 *         .build();
 * ```
 *
 * Without a [recipients] provider the channel reaches every online player,
 * like a normal global channel.
 */
class ChannelRegistration private constructor(
    val id: String,
    val displayName: String,
    val format: String,
    val recipients: RecipientProvider?,
) {

    /** Collects the parts. Get one from [ChannelRegistration.builder]. */
    class Builder internal constructor(
        private val id: String,
        private val displayName: String,
    ) {

        private var format = "<white><player><dark_gray>: <white><message>"
        private var recipients: RecipientProvider? = null

        /**
         * The chat format, in MiniMessage, with the usual placeholders
         * (`<player>`, `<message>`, `<prefix>` and the rest).
         */
        fun format(format: String): Builder = apply { this.format = format }

        /** Decides who receives each message. Null, the default, means everyone online. */
        fun recipients(recipients: RecipientProvider?): Builder = apply { this.recipients = recipients }

        fun build(): ChannelRegistration = ChannelRegistration(id, displayName, format, recipients)
    }

    companion object {
        /**
         * Starts a channel with the given id (`a-z`, `0-9`, `-`, `_`) and the
         * name players see in channel lists.
         */
        @JvmStatic
        fun builder(id: String, displayName: String): Builder = Builder(id, displayName)
    }
}
