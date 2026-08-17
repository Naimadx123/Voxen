package zone.vao.voxen.presence

import zone.vao.voxen.config.PresenceConfig
import zone.vao.voxen.network.BrokerMessage
import zone.vao.voxen.network.BrokerService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PresenceService(
    private val settings: () -> PresenceConfig,
    private val serverId: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Entry(val uuid: UUID, val name: String, val server: String, val seenAt: Long)

    @Volatile
    var remotePublisher: ((BrokerMessage) -> Unit)? = null

    private val remote = ConcurrentHashMap<UUID, Entry>()

    fun find(name: String): Entry? {
        if (!settings().enabled) return null
        val fresh = clock() - settings().ttlMillis
        return remote.values.firstOrNull { it.name.equals(name, ignoreCase = true) && it.seenAt >= fresh }
    }

    fun serverOf(name: String): String? = find(name)?.server

    fun names(): List<String> {
        if (!settings().enabled) return emptyList()
        val fresh = clock() - settings().ttlMillis
        return remote.values.filter { it.seenAt >= fresh }.map { it.name }
    }

    fun entries(): List<Entry> {
        if (!settings().enabled) return emptyList()
        val fresh = clock() - settings().ttlMillis
        return remote.values.filter { it.seenAt >= fresh }.sortedWith(compareBy({ it.server }, { it.name }))
    }

    fun announceJoin(uuid: UUID, name: String) {
        publish(BrokerService.TYPE_PRESENCE_JOIN) { it.copy(sender = name, senderUuid = uuid.toString()) }
    }

    fun announceQuit(uuid: UUID, name: String) {
        publish(BrokerService.TYPE_PRESENCE_QUIT) { it.copy(sender = name, senderUuid = uuid.toString()) }
    }

    fun announceRoster(local: Collection<Pair<UUID, String>>) {
        publish(BrokerService.TYPE_PRESENCE_SYNC) { message ->
            message.copy(roster = local.map { "${it.first}:${it.second}" })
        }
    }

    fun handleRemote(message: BrokerMessage) {
        if (!settings().enabled) return
        val from = message.server?.takeIf { it.isNotEmpty() && it != serverId() } ?: return
        when (message.type) {
            BrokerService.TYPE_PRESENCE_JOIN -> parse(message.senderUuid, message.sender)?.let { (uuid, name) ->
                remote[uuid] = Entry(uuid, name, from, clock())
            }

            BrokerService.TYPE_PRESENCE_QUIT -> parse(message.senderUuid, message.sender)?.let { (uuid, _) ->
                remote.remove(uuid)
            }

            BrokerService.TYPE_PRESENCE_SYNC -> replaceServer(from, message.roster.orEmpty())
        }
        purge()
    }

    fun forget(uuid: UUID) {
        remote.remove(uuid)
    }

    fun clear() {
        remote.clear()
    }

    private fun replaceServer(from: String, roster: List<String>) {
        val now = clock()
        val listed = HashSet<UUID>(roster.size)
        for (raw in roster) {
            val (uuid, name) = parse(raw.substringBefore(':'), raw.substringAfter(':', "")) ?: continue
            listed += uuid
            remote[uuid] = Entry(uuid, name, from, now)
        }
        remote.values.removeIf { it.server == from && it.uuid !in listed }
    }

    private fun purge() {
        val cutoff = clock() - settings().ttlMillis
        remote.values.removeIf { it.seenAt < cutoff }
    }

    private fun parse(rawUuid: String?, name: String?): Pair<UUID, String>? {
        val uuid = runCatching { UUID.fromString(rawUuid) }.getOrNull() ?: return null
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > 16 || ':' in trimmed) return null
        return uuid to trimmed
    }

    private fun publish(type: String, fill: (BrokerMessage) -> BrokerMessage) {
        if (!settings().enabled) return
        val publisher = remotePublisher ?: return
        publisher(
            fill(
                BrokerMessage(
                    id = UUID.randomUUID().toString(),
                    server = serverId(),
                    channel = null,
                    sender = null,
                    component = null,
                    type = type,
                )
            )
        )
    }
}
