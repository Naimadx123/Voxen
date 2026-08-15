package zone.vao.voxen.config

data class NetworkConfig(
    val transport: Transport,
    val serverId: String,
    val reconnectSeconds: Long,
    val timeoutMillis: Long,
    val redis: Redis,
    val nats: Nats,
    val rabbit: Rabbit,
) {
    enum class Transport {
        NONE,
        REDIS,
        NATS,
        RABBITMQ;

        companion object {
            fun from(value: String?): Transport =
                entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: NONE
        }
    }

    data class Redis(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val ssl: Boolean,
        val channel: String,
    )

    data class Nats(
        val url: String,
        val username: String,
        val password: String,
        val subject: String,
    )

    data class Rabbit(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val virtualHost: String,
        val exchange: String,
    )
}
