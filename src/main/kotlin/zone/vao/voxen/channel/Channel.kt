package zone.vao.voxen.channel

import zone.vao.voxen.config.SoundConfig

data class Channel(
    val id: String,
    val displayName: String,
    val type: ChannelType,
    val enabled: Boolean,
    val defaultChannel: Boolean,
    val defaultActive: Boolean,
    val readOnly: Boolean,
    val crossServer: Boolean,
    val radius: Int,
    val worlds: Set<String>,
    val format: String,
    val groupFormats: Map<String, String>,
    val worldFormats: Map<String, String>,
    val consoleFormat: String?,
    val externalFormat: String?,
    val aliases: List<String>,
    val quickPrefix: String?,
    val cooldownMillis: Long,
    val readPermission: String?,
    val writePermission: String?,
    val joinPermission: String?,
    val managePermission: String?,
    val emptyWarning: Boolean,
    val itemTags: Boolean,
    val scope: String?,
    val discord: Boolean,
    val discordFormat: String?,
    val sound: SoundConfig,
) {
    fun canRead(permissible: org.bukkit.permissions.Permissible): Boolean =
        readPermission == null || permissible.hasPermission(readPermission)

    fun canWrite(permissible: org.bukkit.permissions.Permissible): Boolean =
        writePermission == null || permissible.hasPermission(writePermission)

    fun canJoin(permissible: org.bukkit.permissions.Permissible): Boolean =
        joinPermission == null || permissible.hasPermission(joinPermission)

    fun canManage(permissible: org.bukkit.permissions.Permissible): Boolean =
        managePermission == null || permissible.hasPermission(managePermission)

    fun allowsWorld(world: String): Boolean =
        worlds.isEmpty() || worlds.any { it.equals(world, ignoreCase = true) }
}
