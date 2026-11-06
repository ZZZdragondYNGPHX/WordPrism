package dev.wordprism.android

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.wordprism.engine.Database as CoreDatabase
import dev.wordprism.engine.StateCodec
import dev.wordprism.engine.StateStore
import dev.wordprism.engine.StorageException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

@Entity(tableName = "records", primaryKeys = ["bucket", "record_key"], indices = [Index(value = ["bucket"])])
data class StoredRecord(val bucket: String, @ColumnInfo(name = "record_key") val key: String, val payload: String)
data class LargeRecordKey(val bucket: String, @ColumnInfo(name = "record_key") val key: String,
    @ColumnInfo(name = "characters") val characters: Int)
@Dao interface RecordDao {
    @Query("SELECT * FROM records ORDER BY bucket, record_key") suspend fun all(): List<StoredRecord>
    @Upsert suspend fun put(records: List<StoredRecord>)
    @Query("DELETE FROM records WHERE bucket=:bucket AND record_key=:key") suspend fun delete(bucket: String, key: String)
    @Query("SELECT payload FROM records WHERE bucket='_root' AND record_key='revision'") fun revision(): Flow<String?>
    @Query("SELECT payload FROM records WHERE bucket='_root' AND record_key='revision'") suspend fun currentRevision(): String?
    @Query("SELECT * FROM records WHERE length(CAST(payload AS BLOB)) < 1048576") suspend fun smallRecords(): List<StoredRecord>
    @Query("SELECT bucket, record_key, length(payload) AS characters FROM records WHERE length(CAST(payload AS BLOB)) >= 1048576") suspend fun largeRecords(): List<LargeRecordKey>
    @Query("SELECT substr(payload, :start, 32768) FROM records WHERE bucket=:bucket AND record_key=:key") suspend fun payloadChunk(bucket: String, key: String, start: Int): String
}
@Database(entities = [StoredRecord::class], version = 2, exportSchema = true)
abstract class WordPrismDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("CREATE INDEX IF NOT EXISTS index_records_bucket ON records(bucket)") }
        }
        fun file(context: Context, name: String = "wordprism-core.db"): File {
            require(Regex("[A-Za-z0-9_.-]{1,120}\\.db").matches(name))
            val directory = File(context.noBackupFilesDir, "wordprism").also { check(it.mkdirs() || it.isDirectory) }
            return File(directory, name)
        }
        fun open(context: Context, name: String = "wordprism-core.db"): WordPrismDatabase = Room.databaseBuilder(context.applicationContext, WordPrismDatabase::class.java, file(context, name).absolutePath)
            .addMigrations(MIGRATION_1_2).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
    }
}
class RoomStateStore(private val database: WordPrismDatabase) : StateStore {
    private val dao get() = database.records()
    private val mutex = Mutex()
    private var cached: CoreDatabase? = null
    private suspend fun loadRows(): List<StateCodec.Row> {
        return try { dao.all().map { StateCodec.Row(it.bucket, it.key, it.payload) } }
        catch (_: android.database.sqlite.SQLiteBlobTooBigException) {
            // Full backup previews can exceed Android's CursorWindow limit. Keep the on-disk format;
            // only transport those strings through bounded SQLite substr projections.
            val rows = dao.smallRecords().map { StateCodec.Row(it.bucket, it.key, it.payload) }.toMutableList()
            dao.largeRecords().forEach { key ->
                val payload = buildString { for (start in 1..key.characters step 32768) append(dao.payloadChunk(key.bucket, key.key, start)) }
                rows += StateCodec.Row(key.bucket, key.key, payload)
            }
            rows
        }
    }
    // Check SQLite's revision, including writes from another Room instance. A transaction pins the snapshot.
    private suspend fun snapshot(): CoreDatabase {
        val revision = dao.currentRevision()?.toLongOrNull() ?: 0L
        return cached?.takeIf { it.revision == revision }
            ?: StateCodec.decode(loadRows()).also { cached = it }
    }
    override suspend fun read(): CoreDatabase = withContext(Dispatchers.IO) { mutex.withLock { storage {
        database.withTransaction { StateCodec.fork(snapshot()) }
    } } }
    override suspend fun <T> transaction(block: (CoreDatabase) -> T): T = withContext(Dispatchers.IO) { mutex.withLock { storage {
        var committed: CoreDatabase? = null
        val result = database.withTransaction {
            val initializing = dao.currentRevision() == null
            val before = snapshot(); val db = StateCodec.fork(before)
            val result = block(db)
            val changes = if (initializing) {
                db.revision = before.revision + 1
                StateCodec.Changes(StateCodec.rows(db), emptyList())
            } else StateCodec.changes(before, db)
            changes?.let {
                changes.deletes.forEach { (bucket, key) -> dao.delete(bucket, key) }
                dao.put(changes.writes.map { StoredRecord(it.bucket, it.key, it.payload) })
            }
            committed = StateCodec.fork(db)
            result
        }
        // Publish only after SQLite commits. Failed/cancelled transactions cannot poison the cache.
        cached = committed
        result
    } } }
    override fun revisions(): Flow<Long> = dao.revision().map { it?.toLongOrNull() ?: 0 }.distinctUntilChanged()
        .catch { if (it is android.database.SQLException) throw StorageException(it) else throw it }
    override fun close() = database.close()
}

private suspend inline fun <T> storage(block: () -> T): T = try { block() } catch (e: android.database.SQLException) { throw StorageException(e) }
