package zone.vao.voxen.storage

enum class StorageType {
    SQLITE,
    MYSQL,
    MARIADB,
    POSTGRES;

    companion object {
        fun from(value: String?): StorageType = when (value?.trim()?.lowercase()) {
            "mysql" -> MYSQL
            "mariadb" -> MARIADB
            "postgres", "postgresql" -> POSTGRES
            else -> SQLITE
        }
    }
}
