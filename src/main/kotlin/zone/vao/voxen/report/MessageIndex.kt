package zone.vao.voxen.report

import net.kyori.adventure.chat.SignedMessage
import java.util.UUID

class MessageIndex(private val limit: () -> Int) {

    private val entries = LinkedHashMap<UUID, Entry>()

    class Entry(
        val id: UUID,
        val author: UUID,
        val authorName: String,
        val channel: String,
        val content: String,
        val server: String,
        val createdAt: Long,
    ) {

        val token: String = id.toString().replace("-", "").take(TOKEN_LENGTH)

        @Volatile
        var signed: SignedMessage? = null
    }

    fun remember(entry: Entry) {
        synchronized(entries) {
            entries[entry.id] = entry
            val max = limit().coerceAtLeast(1)
            while (entries.size > max) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
    }

    fun attach(id: UUID, signed: SignedMessage) {
        synchronized(entries) { entries[id] }?.signed = signed
    }

    fun get(id: UUID): Entry? = synchronized(entries) { entries[id] }

    fun byToken(token: String): Entry? {
        val wanted = token.removePrefix("#").lowercase()
        if (wanted.length != TOKEN_LENGTH) return null
        return synchronized(entries) { entries.values.lastOrNull { it.token == wanted } }
    }

    fun latest(author: UUID): Entry? =
        synchronized(entries) { entries.values.lastOrNull { it.author == author } }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    companion object {
        const val TOKEN_LENGTH = 8
    }
}
