package zone.vao.voxen.hook

import org.bukkit.entity.Player
import java.util.logging.Logger

class TeamHooks(private val logger: Logger) {

    fun sameTeam(scope: String, a: Player, b: Player): Boolean = when (scope) {
        "towny" -> sameTown(a, b)
        "factions" -> sameFaction(a, b)
        "mcmmo" -> sameMcmmoParty(a, b)
        else -> false
    }

    fun available(scope: String): Boolean = runCatching {
        when (scope) {
            "towny" -> Class.forName("com.palmergames.bukkit.towny.TownyAPI")
            "factions" -> Class.forName("com.massivecraft.factions.FPlayers")
            "mcmmo" -> Class.forName("com.gmail.nossr50.api.PartyAPI")
            else -> return false
        }
        true
    }.getOrDefault(false)

    private fun sameTown(a: Player, b: Player): Boolean = runCatching {
        val api = Class.forName("com.palmergames.bukkit.towny.TownyAPI")
        val instance = api.getMethod("getInstance").invoke(null)
        val getResident = api.getMethod("getResident", Player::class.java)
        val residentA = getResident.invoke(instance, a) ?: return false
        val residentB = getResident.invoke(instance, b) ?: return false
        val getTown = residentA.javaClass.getMethod("getTownOrNull")
        val townA = getTown.invoke(residentA) ?: return false
        val townB = getTown.invoke(residentB) ?: return false
        townA == townB
    }.getOrDefault(false)

    private fun sameFaction(a: Player, b: Player): Boolean = runCatching {
        val fPlayers = Class.forName("com.massivecraft.factions.FPlayers")
        val instance = fPlayers.getMethod("getInstance").invoke(null)
        val getByPlayer = instance.javaClass.getMethod("getByPlayer", Player::class.java)
        val fpA = getByPlayer.invoke(instance, a) ?: return false
        val fpB = getByPlayer.invoke(instance, b) ?: return false
        val getFaction = fpA.javaClass.getMethod("getFaction")
        val factionA = getFaction.invoke(fpA) ?: return false
        val factionB = getFaction.invoke(fpB) ?: return false
        val isWilderness = factionA.javaClass.getMethod("isWilderness")
        if (isWilderness.invoke(factionA) == true) return false
        factionA == factionB
    }.getOrDefault(false)

    private fun sameMcmmoParty(a: Player, b: Player): Boolean = runCatching {
        val api = Class.forName("com.gmail.nossr50.api.PartyAPI")
        val inSameParty = api.getMethod("inSameParty", Player::class.java, Player::class.java)
        inSameParty.invoke(null, a, b) == true
    }.getOrDefault(false)
}
