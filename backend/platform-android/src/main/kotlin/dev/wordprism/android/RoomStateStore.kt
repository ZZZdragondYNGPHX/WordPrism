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
import java.io.File

@Entity(tableName = "records", primaryKeys = ["bucket", "record_key"], indices = [Index(value = ["bucket"])])
data class StoredRecord(val bucket: String, @ColumnInfo(name = "record_key") val key: String, val payload: String)
@Dao interface RecordDao {
    @Query("SELECT * FROM records ORDER BY bucket, record_key") suspend fun all(): List<StoredRecord>
    @Upsert suspend fun put(records: List<StoredRecord>)
    @Query("DELETE FROM records WHERE bucket=:bucket AND record_key=:key") suspend fun delete(bucket: String, key: String)
    @Query("SELECT payload FROM records WHERE bucket='_root' AND record_key='revision'") fun revision(): Flow<String?>
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
    override suspend fun read(): CoreDatabase = storage { StateCodec.decode(dao.all().map { StateCodec.Row(it.bucket, it.key, it.payload) }) }
    override suspend fun <T> transaction(block: (CoreDatabase) -> T): T = storage { database.withTransaction {
        val original = dao.all(); val db = StateCodec.decode(original.map { StateCodec.Row(it.bucket, it.key, it.payload) })
        val result = block(db); val before = original.associate { (it.bucket to it.key) to it.payload }
        StateCodec.rowsForCommit(db, before)?.let { after ->
            val keys = after.map { it.bucket to it.key }.toSet()
            original.filter { (it.bucket to it.key) !in keys }.forEach { dao.delete(it.bucket, it.key) }
            dao.put(after.filter { before[it.bucket to it.key] != it.payload }.map { StoredRecord(it.bucket, it.key, it.payload) })
        }
        result
    } }
    override fun revisions(): Flow<Long> = dao.revision().map { it?.toLongOrNull() ?: 0 }.distinctUntilChanged()
        .catch { if (it is android.database.SQLException) throw StorageException(it) else throw it }
    override fun close() = database.close()
}

private suspend inline fun <T> storage(block: () -> T): T = try { block() } catch (e: android.database.SQLException) { throw StorageException(e) }
