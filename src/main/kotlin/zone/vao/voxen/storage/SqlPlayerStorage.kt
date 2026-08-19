package zone.vao.voxen.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import zone.vao.voxen.moderation.MuteEntry
import zone.vao.voxen.party.PartyRecord
import java.sql.Connection
import java.util.UUID

class SqlPlayerStorage(
    hikariConfig: HikariConfig,
    tablePrefix: String,
    private val type: StorageType,
) : PlayerStorage {

    private val playersTable = "${tablePrefix}players"
    private val ignoresTable = "${tablePrefix}ignores"
    private val mutesTable = "${tablePrefix}mutes"
    private val partiesTable = "${tablePrefix}parties"
    private val partyMembersTable = "${tablePrefix}party_members"
    private val chatLogTable = "${tablePrefix}chat_log"
    private val mailTable = "${tablePrefix}mail"
    private val staffNotesTable = "${tablePrefix}staff_notes"
    private val schemaTable = "${tablePrefix}schema"
    private val dataSource = HikariDataSource(hikariConfig)

    init {
        dataSource.connection.use { conn -> migrate(conn) }
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
                "INSERT INTO $chatLogTable (uuid, player, channel, content, server, created_at) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                for (entry in entries) {
                    ps.setString(1, entry.uuid.toString())
                    ps.setString(2, entry.playerName)
                    ps.setString(3, entry.channel)
                    ps.setString(4, entry.content)
                    ps.setString(5, entry.server)
                    ps.setLong(6, entry.createdAt)
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
