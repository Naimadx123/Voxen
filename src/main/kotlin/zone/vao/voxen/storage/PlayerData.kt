package zone.vao.voxen.storage

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerData(
    val uuid: UUID,
    @Volatile var activeChannel: String?,
    joinedChannels: Collection<String>,
    leftChannels: Collection<String>,
    @Volatile var pmEnabled: Boolean,
    @Volatile var mentionsEnabled: Boolean,
    @Volatile var chatEnabled: Boolean,
    @Volatile var socialSpy: Boolean,
    @Volatile var language: String?,
    @Volatile var filterEnabled: Boolean = true,
    @Volatile var nickname: String? = null,
    @Volatile var lastPmUuid: String? = null,
    @Volatile var lastPmName: String? = null,
) {
    val joinedChannels: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { addAll(joinedChannels) }
    val leftChannels: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { addAll(leftChannels) }

    @Volatile private var plainSource: String? = null
    @Volatile private var plainValue: String = ""

    fun plainNickname(render: (String) -> String): String? {
        val current = nickname ?: return null
        if (current != plainSource) {
            plainValue = render(current)
            plainSource = current
        }
        return plainValue
    }

    companion object {
        fun fresh(uuid: UUID): PlayerData =
            PlayerData(
                uuid, null, emptySet(), emptySet(),
                pmEnabled = true, mentionsEnabled = true, chatEnabled = true, socialSpy = false, language = null,
            )
    }
}
