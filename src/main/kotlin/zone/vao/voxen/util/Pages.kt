package zone.vao.voxen.util

object Pages {

    const val SIZE = 10

    data class Page<T>(val items: List<T>, val number: Int, val count: Int, val offset: Int) {
        val hasNext: Boolean get() = number < count
    }

    fun <T> of(items: List<T>, page: Int, size: Int = SIZE): Page<T> {
        val count = maxOf(1, (items.size + size - 1) / size)
        val number = page.coerceIn(1, count)
        val offset = (number - 1) * size
        return Page(items.subList(offset, minOf(items.size, offset + size)), number, count, offset)
    }
}
