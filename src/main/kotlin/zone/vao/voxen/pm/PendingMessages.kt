package zone.vao.voxen.pm

import net.kyori.adventure.text.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PendingMessages {

    class Entry(val senderUuid: UUID, val targetName: String, val message: Component)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun add(requestId: String, senderUuid: UUID, targetName: String, message: Component) {
        entries[requestId] = Entry(senderUuid, targetName, message)
    }

    fun claim(requestId: String?, senderUuid: UUID?, targetName: String?): Entry? {
        if (requestId == null || senderUuid == null) return null
        val entry = entries[requestId] ?: return null
        if (entry.senderUuid != senderUuid) return null
        if (targetName != null && !targetName.equals(entry.targetName, ignoreCase = true)) return null
        return if (entries.remove(requestId, entry)) entry else null
    }

    fun drop(requestId: String): Entry? = entries.remove(requestId)

    fun forget(senderUuid: UUID) {
        entries.values.removeIf { it.senderUuid == senderUuid }
    }

    fun size(): Int = entries.size
}
