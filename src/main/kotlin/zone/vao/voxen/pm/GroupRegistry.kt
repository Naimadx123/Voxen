package zone.vao.voxen.pm

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GroupRegistry(
    private val idleMillis: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    class Group(val id: UUID, val members: LinkedHashMap<UUID, String>, @Volatile var lastActivity: Long)

    // ponytail: a plain scan, groups are counted in dozens; index by member if that ever changes
    private val groups = ConcurrentHashMap<UUID, Group>()

    fun of(uuid: UUID): Group? {
        purge()
        return groups.values.firstOrNull { uuid in it.members }
    }

    fun create(members: LinkedHashMap<UUID, String>): Group {
        val group = Group(UUID.randomUUID(), members, clock())
        groups[group.id] = group
        return group
    }

    fun merge(id: UUID, members: LinkedHashMap<UUID, String>): Group? {
        purge()
        if (members.size < 2) {
            groups.remove(id)
            return null
        }
        return groups.compute(id) { _, existing ->
            existing?.also {
                it.members.clear()
                it.members.putAll(members)
                it.lastActivity = clock()
            } ?: Group(id, members, clock())
        }
    }

    fun touch(group: Group) {
        group.lastActivity = clock()
    }

    fun remove(id: UUID) {
        groups.remove(id)
    }

    fun clear() {
        groups.clear()
    }

    fun purge() {
        val cutoff = clock() - idleMillis()
        groups.values.removeIf { it.lastActivity < cutoff }
    }

    fun size(): Int {
        purge()
        return groups.size
    }
}
