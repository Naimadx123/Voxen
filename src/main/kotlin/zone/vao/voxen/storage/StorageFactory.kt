package zone.vao.voxen.storage

import com.zaxxer.hikari.HikariConfig
import org.bukkit.plugin.Plugin
import java.io.File

object StorageFactory {

    fun create(plugin: Plugin, config: StorageConfig): PlayerStorage =
        SqlPlayerStorage(buildHikari(plugin, config), config.tablePrefix)

    private fun buildHikari(plugin: Plugin, config: StorageConfig): HikariConfig {
        val hikari = HikariConfig()
        hikari.poolName = "voxen-pool"

        when (config.type) {
            StorageType.SQLITE -> {
                val file = File(plugin.dataFolder, "data.db")
                file.parentFile?.mkdirs()
                hikari.jdbcUrl = "jdbc:sqlite:${file.absolutePath}"
                hikari.driverClassName = "org.sqlite.JDBC"
                hikari.maximumPoolSize = 1
                hikari.connectionInitSql = "PRAGMA busy_timeout=5000"
            }

            StorageType.MYSQL, StorageType.MARIADB -> {
                hikari.jdbcUrl = "jdbc:mysql://${config.host}:${config.port}/${config.database}"
                hikari.driverClassName = "com.mysql.cj.jdbc.Driver"
                hikari.username = config.username
                hikari.password = config.password
                hikari.maximumPoolSize = config.poolSize
            }
        }

        runCatching { Class.forName(hikari.driverClassName) }
            .onFailure { throw IllegalStateException("JDBC driver ${hikari.driverClassName} is unavailable", it) }
        return hikari
    }
}
