package zone.vao.voxen.tags

object LegacyColors {

    private val HEX = Regex("&#([0-9a-fA-F]{6})")

    private val COLORS = mapOf(
        '0' to "black", '1' to "dark_blue", '2' to "dark_green", '3' to "dark_aqua",
        '4' to "dark_red", '5' to "dark_purple", '6' to "gold", '7' to "gray",
        '8' to "dark_gray", '9' to "blue", 'a' to "green", 'b' to "aqua",
        'c' to "red", 'd' to "light_purple", 'e' to "yellow", 'f' to "white",
    )

    private val FORMATS = mapOf(
        'l' to "b", 'o' to "i", 'n' to "u", 'm' to "st", 'k' to "obf",
    )

    fun translate(input: String, allowColor: Boolean, allowHex: Boolean, allowFormat: Boolean): String {
        var result = input
        if (allowHex) {
            result = HEX.replace(result) { "<#${it.groupValues[1]}>" }
        }
        if (!allowColor && !allowFormat) return result
        val builder = StringBuilder(result.length)
        var i = 0
        while (i < result.length) {
            val ch = result[i]
            if (ch == '&' && i + 1 < result.length) {
                val code = result[i + 1].lowercaseChar()
                val color = COLORS[code]
                val format = FORMATS[code]
                when {
                    allowColor && color != null -> {
                        builder.append('<').append(color).append('>')
                        i += 2
                        continue
                    }
                    allowFormat && format != null -> {
                        builder.append('<').append(format).append('>')
                        i += 2
                        continue
                    }
                    (allowColor || allowFormat) && code == 'r' -> {
                        builder.append("<reset>")
                        i += 2
                        continue
                    }
                }
            }
            builder.append(ch)
            i++
        }
        return builder.toString()
    }
}
