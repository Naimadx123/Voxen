package zone.vao.voxen.hook

import org.bukkit.entity.Player

interface MetaProvider {
    fun prefix(player: Player): String
    fun suffix(player: Player): String
    fun group(player: Player): String

    object None : MetaProvider {
        override fun prefix(player: Player): String = ""
        override fun suffix(player: Player): String = ""
        override fun group(player: Player): String = "default"
    }
}
