package zone.vao.voxen.channel

enum class ChannelType {
    GLOBAL,
    LOCAL,
    WORLD,
    SERVER,
    STAFF,
    PARTY,
    CUSTOM;

    companion object {
        fun from(value: String?): ChannelType =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: CUSTOM
    }
}
