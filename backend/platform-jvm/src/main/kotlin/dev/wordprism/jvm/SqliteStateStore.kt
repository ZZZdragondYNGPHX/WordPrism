package dev.wordprism.jvm

import dev.wordprism.engine.*
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Real SQLite persistence for headless integration tests and local tooling. Not packaged on Android. */
class SqliteStateStore(path: Path) : StateStore {
    private val connection: Connection = storage { DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}") }
    private val mutex = Mutex()
    private val revisions = MutableStateFlow(0L)
    init {
        storage { connection.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA synchronous=FULL")
            it.execute("PRAGMA busy_timeout=5000")
            it.execute("CREATE TABLE IF NOT EXISTS records (bucket TEXT NOT NULL, record_key TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(bucket, record_key))")
        } }
    }
    private fun rows(): List<StateCodec.Row> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT bucket, record_key, payload FROM records ORDER BY bucket, record_key").use { result ->
            buildList { while (result.next()) add(StateCodec.Row(result.getString(1), result.getString(2), result.getString(3))) }
        }
    }
    override suspend fun read(): Database = withContext(Dispatchers.IO) { mutex.withLock { storage { StateCodec.decode(rows()) } } }
    override suspend fun <T> transaction(block: (Database) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { storage {
            connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            try {
                val before = rows(); val db = StateCodec.decode(before); val result = block(db)
                val beforeMap = before.associate { (it.bucket to it.key) to it.payload }
                if (StateCodec.rows(db).associate { (it.bucket to it.key) to it.payload } != beforeMap) {
                    db.revision++
                    val after = StateCodec.rows(db); val afterKeys = after.map { it.bucket to it.key }.toSet()
                    connection.prepareStatement("DELETE FROM records WHERE bucket=? AND record_key=?").use { delete ->
                        before.filter { (it.bucket to it.key) !in afterKeys }.forEach { row -> delete.setString(1, row.bucket); delete.setString(2, row.key); delete.addBatch() }; delete.executeBatch()
                    }
                    connection.prepareStatement("INSERT INTO records(bucket,record_key,payload) VALUES(?,?,?) ON CONFLICT(bucket,record_key) DO UPDATE SET payload=excluded.payload").use { insert ->
                        after.filter { beforeMap[it.bucket to it.key] != it.payload }.forEach { row -> insert.setString(1,row.bucket); insert.setString(2,row.key); insert.setString(3,row.payload); insert.addBatch() }; insert.executeBatch()
                    }
                }
                connection.createStatement().use { it.execute("COMMIT") }
                revisions.value = db.revision
                result
            } catch (e: Throwable) {
                try { connection.createStatement().use { it.execute("ROLLBACK") } } catch (rollback: SQLException) { e.addSuppressed(rollback) }
                throw e
            }
        } }
    }
    override fun revisions(): Flow<Long> = revisions
    override fun close() = storage { connection.close() }
}

private inline fun <T> storage(block: () -> T): T = try { block() } catch (e: SQLException) { throw StorageException(e) }
