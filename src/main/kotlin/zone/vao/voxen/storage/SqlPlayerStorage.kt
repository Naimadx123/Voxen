package zone.vao.voxen.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import zone.vao.voxen.moderation.MuteEntry
import zone.vao.voxen.party.PartyRecord
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

class SqlPlayerStorage(
    hikariConfig: HikariConfig,
    tablePrefix: String,
    private val type: StorageType,
) : PlayerStorage {

    init {
        require(tablePrefix.matches(Regex("[A-Za-z0-9_]*"))) {
            "table-prefix '$tablePrefix' may only contain letters, digits and underscores"
        }
    }

    private val playersTable = "${tablePrefix}players"
    private val ignoresTable = "${tablePrefix}ignores"
    private val mutesTable = "${tablePrefix}mutes"
    private val partiesTable = "${tablePrefix}parties"
    private val partyMembersTable = "${tablePrefix}party_members"
    private val chatLogTable = "${tablePrefix}chat_log"
    private val mailTable = "${tablePrefix}mail"
    private val staffNotesTable = "${tablePrefix}staff_notes"
    private val reportsTable = "${tablePrefix}reports"
    private val reportActionsTable = "${tablePrefix}report_actions"
    private val schemaTable = "${tablePrefix}schema"
    private val reportColumns =
        "id, target, target_name, reporter, reporter_name, reason, server, status, handler, created_at, updated_at, " +
            "channel, message_id, message_content, message_at"
    private val chatLogColumns = "uuid, player, channel, content, server, created_at, id"
    private val dataSource = HikariDataSource(hikariConfig)

    init {
        dataSource.connection.use { conn ->
            val lock = lockStatement(tablePrefix)
            if (lock != null) conn.createStatement().use { st -> st.executeQuery(lock).use { it.next() } }
            try {
                migrate(conn)
            } finally {
                val release = unlockStatement(tablePrefix)
                if (release != null) runCatching { conn.createStatement().use { st -> st.executeQuery(release).use { } } }
            }
        }
    }

    private fun lockStatement(prefix: String): String? = when (type) {
        StorageType.MYSQL, StorageType.MARIADB -> "SELECT GET_LOCK('${prefix}migrate', 30)"
        StorageType.POSTGRES -> "SELECT pg_advisory_lock(${"${prefix}migrate".hashCode()})"
        StorageType.SQLITE -> null
    }

    private fun unlockStatement(prefix: String): String? = when (type) {
        StorageType.MYSQL, StorageType.MARIADB -> "SELECT RELEASE_LOCK('${prefix}migrate')"
        StorageType.POSTGRES -> "SELECT pg_advisory_unlock(${"${prefix}migrate".hashCode()})"
        StorageType.SQLITE -> null
    }

    private fun migrate(conn: Connection) {
        conn.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE IF NOT EXISTS $schemaTable (version INT NOT NULL)")
        }
        var version = conn.prepareStatement("SELECT version FROM $schemaTable").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        if (version < 1) {
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS $playersTable " +
                        "(uuid VARCHAR(36) PRIMARY KEY, active_channel VARCHAR(64), joined_channels TEXT, left_channels TEXT, " +
                        "pm_enabled INT NOT NULL, mentions_enabled INT NOT NULL, chat_enabled INT NOT NULL, " +
                        "social_spy INT NOT NULL, language VARCHAR(16))"
                )
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS $ignoresTable " +
                        "(uuid VARCHAR(36) NOT NULL, ignored VARCHAR(36) NOT NULL, PRIMARY KEY (uuid, ignored))"
                )
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS $mutesTable " +
                        "(uuid VARCHAR(36) NOT NULL, player VARCHAR(16) NOT NULL, channel VARCHAR(64) NOT NULL, " +
                        "reason TEXT, moderator VARCHAR(32) NOT NULL, expires_at BIGINT, created_at BIGINT NOT NULL, " +
                        "PRIMARY KEY (uuid, channel))"
                )
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS $partiesTable " +
                        "(id VARCHAR(36) PRIMARY KEY, name VARCHAR(32) NOT NULL, leader VARCHAR(36) NOT NULL)"
                )
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS $partyMembersTable " +
                        "(party_id VARCHAR(36) NOT NULL, uuid VARCHAR(36) PRIMARY KEY)"
                )
            }
            writeVersion(conn, 1)
            version = 1
        }
        if (version < 2) {
            addColumn(conn, playersTable, "filter_enabled", "INT NOT NULL DEFAULT 1")
            addColumn(conn, playersTable, "nickname", "VARCHAR(64)")
            writeVersion(conn, 2)
            version = 2
        }
        if (version < 3) {
            addColumn(conn, playersTable, "last_pm_uuid", "VARCHAR(36)")
            addColumn(conn, playersTable, "last_pm_name", "VARCHAR(16)")
            writeVersion(conn, 3)
            version = 3
        }
        if (version < 4) {
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS $chatLogTable " +
                        "(uuid VARCHAR(36) NOT NULL, player VARCHAR(16) NOT NULL, channel VARCHAR(64) NOT NULL, " +
                        "content TEXT NOT NULL, server VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL)"
                )
                runCatching {
                    st.executeUpdate("CREATE INDEX ${chatLogTable}_lookup ON $chatLogTable (uuid, created_at)")
                }
            }
            writeVersion(conn, 4)
            version = 4
        }
        if (version < 5) {
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS $mailTable " +
                        "(id VARCHAR(36) PRIMARY KEY, recipient VARCHAR(36) NOT NULL, sender VARCHAR(36) NOT NULL, " +
                        "sender_name VARCHAR(16) NOT NULL, content TEXT NOT NULL, server VARCHAR(64) NOT NULL, " +
                        "created_at BIGINT NOT NULL, read_at BIGINT)"
                )
                runCatching {
                    st.executeUpdate("CREATE INDEX ${mailTable}_inbox ON $mailTable (recipient, created_at)")
                }
            }
            writeVersion(conn, 5)
            version = 5
        }
        if (version < 6) {
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS $staffNotesTable " +
                        "(id VARCHAR(36) PRIMARY KEY, target VARCHAR(36) NOT NULL, target_name VARCHAR(16) NOT NULL, " +
                        "author VARCHAR(32) NOT NULL, content TEXT NOT NULL, kind VARCHAR(8) NOT NULL, " +
                        "created_at BIGINT NOT NULL)"
                )
                runCatching {
                    st.executeUpdate(
                        "CREATE INDEX ${staffNotesTable}_lookup ON $staffNotesTable (target, kind, created_at)"
                    )
                }
            }
            writeVersion(conn, 6)
            version = 6
        }
        if (version < 7) {
            addColumn(conn, playersTable, "last_name", "VARCHAR(16)")
            conn.createStatement().use { st ->
                runCatching { st.executeUpdate("CREATE INDEX ${playersTable}_name ON $playersTable (last_name)") }
            }
            writeVersion(conn, 7)
            version = 7
        }
        if (version < 8) {
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS $reportsTable " +
                        "(id VARCHAR(36) PRIMARY KEY, target VARCHAR(36) NOT NULL, target_name VARCHAR(16) NOT NULL, " +
                        "reporter VARCHAR(36) NOT NULL, reporter_name VARCHAR(16) NOT NULL, reason TEXT NOT NULL, " +
                        "server VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, handler VARCHAR(32), " +
                        "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)"
                )
                runCatching {
                    st.executeUpdate("CREATE INDEX ${reportsTable}_queue ON $reportsTable (status, created_at)")
                }
                runCatching {
                    st.executeUpdate("CREATE INDEX ${reportsTable}_target ON $reportsTable (target, created_at)")
                }
            }
            writeVersion(conn, 8)
            version = 8
        }
        if (version < 9) {
            addColumn(conn, reportsTable, "channel", "VARCHAR(64)")
            addColumn(conn, reportsTable, "message_id", "VARCHAR(36)")
            addColumn(conn, reportsTable, "message_content", "TEXT")
            addColumn(conn, reportsTable, "message_at", "BIGINT")
            addColumn(conn, chatLogTable, "id", "VARCHAR(36)")
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS $reportActionsTable " +
                        "(id VARCHAR(36) PRIMARY KEY, report VARCHAR(36) NOT NULL, actor VARCHAR(32) NOT NULL, " +
                        "action VARCHAR(32) NOT NULL, detail TEXT, created_at BIGINT NOT NULL)"
                )
                runCatching {
                    st.executeUpdate(
                        "CREATE INDEX ${reportActionsTable}_report ON $reportActionsTable (report, created_at)"
                    )
                }
                runCatching {
                    st.executeUpdate("CREATE INDEX ${reportActionsTable}_recent ON $reportActionsTable (created_at)")
                }
                runCatching {
                    st.executeUpdate("CREATE INDEX ${chatLogTable}_channel ON $chatLogTable (channel, created_at)")
                }
            }
            writeVersion(conn, 9)
        }
    }

    private fun addColumn(conn: Connection, table: String, column: String, definition: String) {
        val lookup = if (type == StorageType.POSTGRES) table.lowercase() else table
        val existing = conn.metaData.getColumns(null, null, lookup, null).use { rs ->
            buildSet { while (rs.next()) add(rs.getString("COLUMN_NAME").lowercase()) }
        }
        if (column.lowercase() in existing) return
        conn.createStatement().use { st ->
            st.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    private fun writeVersion(conn: Connection, version: Int) {
        val restore = conn.autoCommit
        conn.autoCommit = false
        try {
            conn.createStatement().use { st -> st.executeUpdate("DELETE FROM $schemaTable") }
            conn.prepareStatement("INSERT INTO $schemaTable (version) VALUES (?)").use { ps ->
                ps.setInt(1, version)
                ps.executeUpdate()
            }
            conn.commit()
        } catch (ex: Exception) {
            runCatching { conn.rollback() }
            throw ex
        } finally {
            runCatching { conn.autoCommit = restore }
        }
    }

    override fun loadPlayer(uuid: UUID): PlayerData? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT active_channel, joined_channels, left_channels, pm_enabled, mentions_enabled, chat_enabled, social_spy, language, " +
                    "filter_enabled, nickname, last_pm_uuid, last_pm_name, last_name FROM $playersTable WHERE uuid = ?"
            ).use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return PlayerData(
                        uuid = uuid,
                        activeChannel = rs.getString(1),
                        joinedChannels = splitChannels(rs.getString(2)),
                        leftChannels = splitChannels(rs.getString(3)),
                        pmEnabled = rs.getInt(4) != 0,
                        mentionsEnabled = rs.getInt(5) != 0,
                        chatEnabled = rs.getInt(6) != 0,
                        socialSpy = rs.getInt(7) != 0,
                        language = rs.getString(8),
                        filterEnabled = rs.getInt(9) != 0,
                        nickname = rs.getString(10),
                        lastPmUuid = rs.getString(11),
                        lastPmName = rs.getString(12),
                        lastName = rs.getString(13),
                    )
                }
            }
        }
    }

    override fun savePlayer(data: PlayerData) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                upsert(
                    playersTable,
                    listOf(
                        "uuid", "active_channel", "joined_channels", "left_channels", "pm_enabled", "mentions_enabled",
                        "chat_enabled", "social_spy", "language", "filter_enabled", "nickname", "last_pm_uuid", "last_pm_name",
                        "last_name",
                    ),
                    listOf("uuid"),
                )
            ).use { ps ->
                ps.setString(1, data.uuid.toString())
                ps.setString(2, data.activeChannel)
                ps.setString(3, data.joinedChannels.joinToString(","))
                ps.setString(4, data.leftChannels.joinToString(","))
                ps.setInt(5, if (data.pmEnabled) 1 else 0)
                ps.setInt(6, if (data.mentionsEnabled) 1 else 0)
                ps.setInt(7, if (data.chatEnabled) 1 else 0)
                ps.setInt(8, if (data.socialSpy) 1 else 0)
                ps.setString(9, data.language)
                ps.setInt(10, if (data.filterEnabled) 1 else 0)
                ps.setString(11, data.nickname)
                ps.setString(12, data.lastPmUuid)
                ps.setString(13, data.lastPmName)
                ps.setString(14, data.lastName)
                ps.executeUpdate()
            }
        }
    }

    override fun loadIgnores(uuid: UUID): Set<UUID> {
        val result = LinkedHashSet<UUID>()
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT ignored FROM $ignoresTable WHERE uuid = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) runCatching { result += UUID.fromString(rs.getString(1)) }
                }
            }
        }
        return result
    }

    override fun addIgnore(uuid: UUID, ignored: UUID) {
        dataSource.connection.use { conn ->
            runCatching {
                conn.prepareStatement("INSERT INTO $ignoresTable (uuid, ignored) VALUES (?, ?)").use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.setString(2, ignored.toString())
                    ps.executeUpdate()
                }
            }
        }
    }

    override fun removeIgnore(uuid: UUID, ignored: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM $ignoresTable WHERE uuid = ? AND ignored = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.setString(2, ignored.toString())
                ps.executeUpdate()
            }
        }
    }

    override fun loadMutes(): List<MuteEntry> {
        val result = ArrayList<MuteEntry>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT uuid, player, channel, reason, moderator, expires_at, created_at FROM $mutesTable"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val uuid = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        val expires = rs.getLong(6).let { if (rs.wasNull()) null else it }
                        result += MuteEntry(
                            uuid = uuid,
                            playerName = rs.getString(2),
                            channel = rs.getString(3).ifEmpty { null },
                            reason = rs.getString(4),
                            moderator = rs.getString(5),
                            expiresAt = expires,
                            createdAt = rs.getLong(7),
                        )
                    }
                }
            }
        }
        return result
    }

    override fun saveMute(entry: MuteEntry) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                upsert(
                    mutesTable,
                    listOf("uuid", "player", "channel", "reason", "moderator", "expires_at", "created_at"),
                    listOf("uuid", "channel"),
                )
            ).use { ps ->
                ps.setString(1, entry.uuid.toString())
                ps.setString(2, entry.playerName)
                ps.setString(3, entry.channel.orEmpty())
                ps.setString(4, entry.reason)
                ps.setString(5, entry.moderator)
                if (entry.expiresAt != null) ps.setLong(6, entry.expiresAt) else ps.setNull(6, java.sql.Types.BIGINT)
                ps.setLong(7, entry.createdAt)
                ps.executeUpdate()
            }
        }
    }

    override fun deleteMute(uuid: UUID, channel: String?) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM $mutesTable WHERE uuid = ? AND channel = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.setString(2, channel.orEmpty())
                ps.executeUpdate()
            }
        }
    }

    override fun loadParties(): List<PartyRecord> {
        val members = HashMap<String, MutableSet<UUID>>()
        val result = ArrayList<PartyRecord>()
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT party_id, uuid FROM $partyMembersTable").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val member = runCatching { UUID.fromString(rs.getString(2)) }.getOrNull() ?: continue
                        members.getOrPut(rs.getString(1)) { LinkedHashSet() } += member
                    }
                }
            }
            conn.prepareStatement("SELECT id, name, leader FROM $partiesTable").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        val leader = runCatching { UUID.fromString(rs.getString(3)) }.getOrNull() ?: continue
                        result += PartyRecord(
                            id = id,
                            name = rs.getString(2),
                            leader = leader,
                            members = members[id.toString()].orEmpty(),
                        )
                    }
                }
            }
        }
        return result
    }

    override fun saveParty(record: PartyRecord) {
        transaction { conn ->
            conn.prepareStatement("DELETE FROM $partiesTable WHERE id = ?").use { ps ->
                ps.setString(1, record.id.toString())
                ps.executeUpdate()
            }
            conn.prepareStatement("INSERT INTO $partiesTable (id, name, leader) VALUES (?, ?, ?)").use { ps ->
                ps.setString(1, record.id.toString())
                ps.setString(2, record.name)
                ps.setString(3, record.leader.toString())
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM $partyMembersTable WHERE party_id = ?").use { ps ->
                ps.setString(1, record.id.toString())
                ps.executeUpdate()
            }
            for (member in record.members) {
                runCatching {
                    conn.prepareStatement("INSERT INTO $partyMembersTable (party_id, uuid) VALUES (?, ?)").use { ps ->
                        ps.setString(1, record.id.toString())
                        ps.setString(2, member.toString())
                        ps.executeUpdate()
                    }
                }
            }
        }
    }

    override fun deleteParty(id: UUID) {
        transaction { conn ->
            conn.prepareStatement("DELETE FROM $partyMembersTable WHERE party_id = ?").use { ps ->
                ps.setString(1, id.toString())
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM $partiesTable WHERE id = ?").use { ps ->
                ps.setString(1, id.toString())
                ps.executeUpdate()
            }
        }
    }

    override fun addPartyMember(id: UUID, member: UUID) {
        transaction { conn ->
            conn.prepareStatement("DELETE FROM $partyMembersTable WHERE uuid = ?").use { ps ->
                ps.setString(1, member.toString())
                ps.executeUpdate()
            }
            conn.prepareStatement("INSERT INTO $partyMembersTable (party_id, uuid) VALUES (?, ?)").use { ps ->
                ps.setString(1, id.toString())
                ps.setString(2, member.toString())
                ps.executeUpdate()
            }
        }
    }

    override fun removePartyMember(id: UUID, member: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM $partyMembersTable WHERE party_id = ? AND uuid = ?").use { ps ->
                ps.setString(1, id.toString())
                ps.setString(2, member.toString())
                ps.executeUpdate()
            }
        }
    }

    override fun logChat(entries: List<ChatLogEntry>) {
        if (entries.isEmpty()) return
        transaction { conn ->
            conn.prepareStatement(
                "INSERT INTO $chatLogTable ($chatLogColumns) VALUES (?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                for (entry in entries) {
                    ps.setString(1, entry.uuid.toString())
                    ps.setString(2, entry.playerName)
                    ps.setString(3, entry.channel)
                    ps.setString(4, entry.content)
                    ps.setString(5, entry.server)
                    ps.setLong(6, entry.createdAt)
                    ps.setString(7, entry.id.toString())
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    override fun chatHistory(uuid: UUID, limit: Int): List<ChatLogEntry> {
        val result = ArrayList<ChatLogEntry>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT player, channel, content, server, created_at FROM $chatLogTable " +
                    "WHERE uuid = ? ORDER BY created_at DESC LIMIT ?"
            ).use { ps ->
                ps.setString(1, uuid.toString())
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result += ChatLogEntry(
                            uuid = uuid,
                            playerName = rs.getString(1),
                            channel = rs.getString(2),
                            content = rs.getString(3),
                            server = rs.getString(4),
                            createdAt = rs.getLong(5),
                        )
                    }
                }
            }
        }
        return result
    }

    override fun chatContext(channel: String, at: Long, before: Int, after: Int): List<ChatLogEntry> {
        val earlier = ArrayList<ChatLogEntry>()
        val later = ArrayList<ChatLogEntry>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT $chatLogColumns FROM $chatLogTable WHERE channel = ? AND created_at <= ? " +
                    "ORDER BY created_at DESC LIMIT ?"
            ).use { ps ->
                ps.setString(1, channel)
                ps.setLong(2, at)
                ps.setInt(3, before.coerceAtLeast(0))
                ps.executeQuery().use { rs -> while (rs.next()) earlier += readChatLog(rs) }
            }
            conn.prepareStatement(
                "SELECT $chatLogColumns FROM $chatLogTable WHERE channel = ? AND created_at > ? " +
                    "ORDER BY created_at ASC LIMIT ?"
            ).use { ps ->
                ps.setString(1, channel)
                ps.setLong(2, at)
                ps.setInt(3, after.coerceAtLeast(0))
                ps.executeQuery().use { rs -> while (rs.next()) later += readChatLog(rs) }
            }
        }
        return earlier.reversed() + later
    }

    private fun readChatLog(rs: ResultSet): ChatLogEntry = ChatLogEntry(
        uuid = runCatching { UUID.fromString(rs.getString(1)) }.getOrDefault(EMPTY_UUID),
        playerName = rs.getString(2),
        channel = rs.getString(3),
        content = rs.getString(4),
        server = rs.getString(5),
        createdAt = rs.getLong(6),
        id = runCatching { UUID.fromString(rs.getString(7)) }.getOrDefault(EMPTY_UUID),
    )

    override fun purgeChatLog(before: Long) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM $chatLogTable WHERE created_at < ?").use { ps ->
                ps.setLong(1, before)
                ps.executeUpdate()
            }
        }
    }

    override fun findByName(name: String): Pair<UUID, String>? {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT uuid, last_name FROM $playersTable WHERE LOWER(last_name) = ? LIMIT 1"
            ).use { ps ->
                ps.setString(1, name.lowercase())
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val uuid = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: return null
                    return uuid to rs.getString(2)
                }
            }
        }
    }

    override fun saveMailIfRoom(entry: MailEntry, max: Int): Boolean =
        transaction { conn ->
            val count = conn.prepareStatement("SELECT COUNT(*) FROM $mailTable WHERE recipient = ?").use { ps ->
                ps.setString(1, entry.recipient.toString())
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
            if (count >= max) false else { insertMail(conn, entry); true }
        }

    override fun saveMail(entry: MailEntry) {
        dataSource.connection.use { conn -> insertMail(conn, entry) }
    }

    private fun insertMail(conn: Connection, entry: MailEntry) {
        conn.prepareStatement(
            "INSERT INTO $mailTable (id, recipient, sender, sender_name, content, server, created_at, read_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { ps ->
            ps.setString(1, entry.id.toString())
            ps.setString(2, entry.recipient.toString())
            ps.setString(3, entry.senderUuid.toString())
            ps.setString(4, entry.senderName)
            ps.setString(5, entry.content)
            ps.setString(6, entry.server)
            ps.setLong(7, entry.createdAt)
            if (entry.readAt != null) ps.setLong(8, entry.readAt) else ps.setNull(8, java.sql.Types.BIGINT)
            ps.executeUpdate()
        }
    }

    override fun mailFor(recipient: UUID, unreadOnly: Boolean): List<MailEntry> {
        val result = ArrayList<MailEntry>()
        val filter = if (unreadOnly) " AND read_at IS NULL" else ""
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT id, sender, sender_name, content, server, created_at, read_at FROM $mailTable " +
                    "WHERE recipient = ?$filter ORDER BY created_at DESC"
            ).use { ps ->
                ps.setString(1, recipient.toString())
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        val sender = runCatching { UUID.fromString(rs.getString(2)) }.getOrNull() ?: continue
                        val readAt = rs.getLong(7).let { if (rs.wasNull()) null else it }
                        result += MailEntry(
                            id = id,
                            recipient = recipient,
                            senderUuid = sender,
                            senderName = rs.getString(3),
                            content = rs.getString(4),
                            server = rs.getString(5),
                            createdAt = rs.getLong(6),
                            readAt = readAt,
                        )
                    }
                }
            }
        }
        return result
    }

    override fun markMailRead(recipient: UUID) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE $mailTable SET read_at = ? WHERE recipient = ? AND read_at IS NULL").use { ps ->
                ps.setLong(1, System.currentTimeMillis())
                ps.setString(2, recipient.toString())
                ps.executeUpdate()
            }
        }
    }

    override fun deleteMail(recipient: UUID, id: UUID): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM $mailTable WHERE recipient = ? AND id = ?").use { ps ->
                ps.setString(1, recipient.toString())
                ps.setString(2, id.toString())
                ps.executeUpdate() > 0
            }
        }

    override fun clearMail(recipient: UUID): Int =
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM $mailTable WHERE recipient = ?").use { ps ->
                ps.setString(1, recipient.toString())
                ps.executeUpdate()
            }
        }

    override fun purgeMail(before: Long) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM $mailTable WHERE created_at < ?").use { ps ->
                ps.setLong(1, before)
                ps.executeUpdate()
            }
        }
    }

    override fun saveReport(entry: ReportEntry) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO $reportsTable ($reportColumns) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, entry.id.toString())
                ps.setString(2, entry.target.toString())
                ps.setString(3, entry.targetName)
                ps.setString(4, entry.reporter.toString())
                ps.setString(5, entry.reporterName)
                ps.setString(6, entry.reason)
                ps.setString(7, entry.server)
                ps.setString(8, entry.status.id)
                ps.setString(9, entry.handler)
                ps.setLong(10, entry.createdAt)
                ps.setLong(11, entry.updatedAt)
                ps.setString(12, entry.channel)
                ps.setString(13, entry.messageId?.toString())
                ps.setString(14, entry.messageContent)
                if (entry.messageAt != null) ps.setLong(15, entry.messageAt) else ps.setNull(15, java.sql.Types.BIGINT)
                ps.executeUpdate()
            }
        }
    }

    override fun reports(statuses: Collection<ReportEntry.Status>, limit: Int): List<ReportEntry> {
        val filter = if (statuses.isEmpty()) "" else " WHERE status IN (${statuses.joinToString(", ") { "?" }})"
        val result = ArrayList<ReportEntry>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT $reportColumns FROM $reportsTable$filter ORDER BY created_at DESC LIMIT ?"
            ).use { ps ->
                var index = 1
                for (status in statuses) ps.setString(index++, status.id)
                ps.setInt(index, limit.coerceAtLeast(1))
                ps.executeQuery().use { rs ->
                    while (rs.next()) result += readReport(rs) ?: continue
                }
            }
        }
        return result
    }

    override fun report(id: UUID): ReportEntry? {
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT $reportColumns FROM $reportsTable WHERE id = ?").use { ps ->
                ps.setString(1, id.toString())
                ps.executeQuery().use { rs -> return if (rs.next()) readReport(rs) else null }
            }
        }
    }

    override fun reportCount(statuses: Collection<ReportEntry.Status>): Int {
        val filter = if (statuses.isEmpty()) "" else " WHERE status IN (${statuses.joinToString(", ") { "?" }})"
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM $reportsTable$filter").use { ps ->
                statuses.forEachIndexed { index, status -> ps.setString(index + 1, status.id) }
                ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    override fun hasOpenReport(reporter: UUID, target: UUID): Boolean {
        val pending = ReportEntry.Status.PENDING
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM $reportsTable WHERE reporter = ? AND target = ? " +
                    "AND status IN (${pending.joinToString(", ") { "?" }})"
            ).use { ps ->
                ps.setString(1, reporter.toString())
                ps.setString(2, target.toString())
                pending.forEachIndexed { index, status -> ps.setString(index + 3, status.id) }
                ps.executeQuery().use { rs -> return rs.next() && rs.getInt(1) > 0 }
            }
        }
    }

    override fun hasReportFor(messageId: UUID): Boolean {
        val pending = ReportEntry.Status.PENDING
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM $reportsTable WHERE message_id = ? " +
                    "AND status IN (${pending.joinToString(", ") { "?" }})"
            ).use { ps ->
                ps.setString(1, messageId.toString())
                pending.forEachIndexed { index, status -> ps.setString(index + 2, status.id) }
                ps.executeQuery().use { rs -> return rs.next() && rs.getInt(1) > 0 }
            }
        }
    }

    override fun updateReport(id: UUID, status: ReportEntry.Status, handler: String?, updatedAt: Long): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE $reportsTable SET status = ?, handler = ?, updated_at = ? WHERE id = ?").use { ps ->
                ps.setString(1, status.id)
                ps.setString(2, handler)
                ps.setLong(3, updatedAt)
                ps.setString(4, id.toString())
                ps.executeUpdate() > 0
            }
        }

    override fun deleteReport(id: UUID): Boolean = transaction { conn ->
        conn.prepareStatement("DELETE FROM $reportActionsTable WHERE report = ?").use { ps ->
            ps.setString(1, id.toString())
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM $reportsTable WHERE id = ?").use { ps ->
            ps.setString(1, id.toString())
            ps.executeUpdate() > 0
        }
    }

    override fun saveReportAction(entry: ReportAction) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO $reportActionsTable (id, report, actor, action, detail, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, entry.id.toString())
                ps.setString(2, entry.report.toString())
                ps.setString(3, entry.actor)
                ps.setString(4, entry.action)
                ps.setString(5, entry.detail)
                ps.setLong(6, entry.createdAt)
                ps.executeUpdate()
            }
        }
    }

    override fun reportActions(report: UUID?, limit: Int): List<ReportAction> {
        val filter = if (report == null) "" else " WHERE report = ?"
        val result = ArrayList<ReportAction>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT id, report, actor, action, detail, created_at FROM $reportActionsTable$filter " +
                    "ORDER BY created_at DESC LIMIT ?"
            ).use { ps ->
                var index = 1
                if (report != null) ps.setString(index++, report.toString())
                ps.setInt(index, limit.coerceAtLeast(1))
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        val owner = runCatching { UUID.fromString(rs.getString(2)) }.getOrNull() ?: continue
                        result += ReportAction(
                            id = id,
                            report = owner,
                            actor = rs.getString(3),
                            action = rs.getString(4),
                            detail = rs.getString(5),
                            createdAt = rs.getLong(6),
                        )
                    }
                }
            }
        }
        return result
    }

    override fun purgeReports(before: Long) {
        val handled = ReportEntry.Status.entries.filterNot { it.pending }
        val placeholders = handled.joinToString(", ") { "?" }
        transaction { conn ->
            conn.prepareStatement(
                "DELETE FROM $reportActionsTable WHERE report IN " +
                    "(SELECT id FROM $reportsTable WHERE updated_at < ? AND status IN ($placeholders))"
            ).use { ps ->
                ps.setLong(1, before)
                handled.forEachIndexed { index, status -> ps.setString(index + 2, status.id) }
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "DELETE FROM $reportsTable WHERE updated_at < ? AND status IN ($placeholders)"
            ).use { ps ->
                ps.setLong(1, before)
                handled.forEachIndexed { index, status -> ps.setString(index + 2, status.id) }
                ps.executeUpdate()
            }
        }
    }

    private fun readReport(rs: ResultSet): ReportEntry? {
        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: return null
        val target = runCatching { UUID.fromString(rs.getString(2)) }.getOrNull() ?: return null
        val reporter = runCatching { UUID.fromString(rs.getString(4)) }.getOrNull() ?: return null
        return ReportEntry(
            id = id,
            target = target,
            targetName = rs.getString(3),
            reporter = reporter,
            reporterName = rs.getString(5),
            reason = rs.getString(6),
            server = rs.getString(7),
            status = ReportEntry.Status.from(rs.getString(8)) ?: ReportEntry.Status.OPEN,
            handler = rs.getString(9),
            createdAt = rs.getLong(10),
            updatedAt = rs.getLong(11),
            channel = rs.getString(12),
            messageId = runCatching { UUID.fromString(rs.getString(13)) }.getOrNull(),
            messageContent = rs.getString(14),
            messageAt = rs.getLong(15).takeIf { !rs.wasNull() },
        )
    }

    override fun saveStaffNote(entry: StaffNote) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO $staffNotesTable (id, target, target_name, author, content, kind, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, entry.id.toString())
                ps.setString(2, entry.target.toString())
                ps.setString(3, entry.targetName)
                ps.setString(4, entry.author)
                ps.setString(5, entry.content)
                ps.setString(6, entry.kind.id)
                ps.setLong(7, entry.createdAt)
                ps.executeUpdate()
            }
        }
    }

    override fun mailCount(recipient: UUID, unreadOnly: Boolean): Int {
        val filter = if (unreadOnly) " AND read_at IS NULL" else ""
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM $mailTable WHERE recipient = ?$filter").use { ps ->
                ps.setString(1, recipient.toString())
                ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    override fun staffNoteCount(target: UUID, kind: StaffNote.Kind, since: Long): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM $staffNotesTable WHERE target = ? AND kind = ? AND created_at >= ?"
            ).use { ps ->
                ps.setString(1, target.toString())
                ps.setString(2, kind.id)
                ps.setLong(3, since)
                ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    override fun staffNotes(target: UUID, kind: StaffNote.Kind, since: Long): List<StaffNote> {
        val result = ArrayList<StaffNote>()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT id, target_name, author, content, created_at FROM $staffNotesTable " +
                    "WHERE target = ? AND kind = ? AND created_at >= ? ORDER BY created_at DESC"
            ).use { ps ->
                ps.setString(1, target.toString())
                ps.setString(2, kind.id)
                ps.setLong(3, since)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val id = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() ?: continue
                        result += StaffNote(
                            id = id,
                            target = target,
                            targetName = rs.getString(2),
                            author = rs.getString(3),
                            content = rs.getString(4),
                            kind = kind,
                            createdAt = rs.getLong(5),
                        )
                    }
                }
            }
        }
        return result
    }

    override fun deleteStaffNote(id: UUID): Boolean =
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM $staffNotesTable WHERE id = ?").use { ps ->
                ps.setString(1, id.toString())
                ps.executeUpdate() > 0
            }
        }

    override fun purgeStaffNotes(before: Long, kind: StaffNote.Kind) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM $staffNotesTable WHERE kind = ? AND created_at < ?").use { ps ->
                ps.setString(1, kind.id)
                ps.setLong(2, before)
                ps.executeUpdate()
            }
        }
    }

    private val EMPTY_UUID: UUID = UUID(0L, 0L)

    private fun <T> transaction(block: (Connection) -> T): T =
        dataSource.connection.use { conn ->
            val restore = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (ex: Exception) {
                runCatching { conn.rollback() }
                throw ex
            } finally {
                runCatching { conn.autoCommit = restore }
            }
        }

    private fun upsert(table: String, columns: List<String>, keys: List<String>): String {
        val head = "INSERT INTO $table (${columns.joinToString(", ")}) " +
            "VALUES (${columns.joinToString(", ") { "?" }})"
        val updates = columns.filterNot { it in keys }
        return when (type) {
            StorageType.SQLITE, StorageType.POSTGRES ->
                "$head ON CONFLICT(${keys.joinToString(", ")}) DO UPDATE SET " +
                    updates.joinToString(", ") { "$it = excluded.$it" }

            StorageType.MYSQL, StorageType.MARIADB ->
                "$head ON DUPLICATE KEY UPDATE " + updates.joinToString(", ") { "$it = VALUES($it)" }
        }
    }

    private fun splitChannels(raw: String?): List<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    override fun close() {
        dataSource.close()
    }
}
