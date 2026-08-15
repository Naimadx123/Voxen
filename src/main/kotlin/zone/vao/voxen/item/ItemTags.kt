package zone.vao.voxen.item

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

object ItemTags {

    val TAG_NAMES = setOf("item", "helmet", "head", "chestplate", "chest", "leggings", "legs", "boots", "feet")

    private val TAG_PATTERN = Regex("<(${TAG_NAMES.joinToString("|")})>", RegexOption.IGNORE_CASE)

    const val PERMISSION = "voxen.chat.tag.item"

    fun containsItemTag(content: String): Boolean = TAG_PATTERN.containsMatchIn(content)

    fun resolvers(player: Player, emptyLabel: Component): TagResolver {
        val inventory = player.inventory
        val slots = mapOf(
            "item" to inventory.itemInMainHand,
            "helmet" to inventory.helmet,
            "head" to inventory.helmet,
            "chestplate" to inventory.chestplate,
            "chest" to inventory.chestplate,
            "leggings" to inventory.leggings,
            "legs" to inventory.leggings,
            "boots" to inventory.boots,
            "feet" to inventory.boots,
        )
        val resolvers = slots.map { (name, item) ->
            TagResolver.resolver(name, Tag.selfClosingInserting(display(item?.clone(), emptyLabel)))
        }
        return TagResolver.resolver(resolvers)
    }

    private fun display(item: ItemStack?, emptyLabel: Component): Component {
        if (item == null || item.type.isAir || item.amount <= 0) return emptyLabel
        var name: Component = item.effectiveName()
        if (item.amount > 1) name = name.append(Component.text(" x${item.amount}"))
        return Component.text()
            .append(Component.text("[", NamedTextColor.GRAY))
            .append(name)
            .append(Component.text("]", NamedTextColor.GRAY))
            .hoverEvent(item.asHoverEvent())
            .build()
    }
}
