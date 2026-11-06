package dev.wordprism.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.wordprism.contract.Id
import dev.wordprism.contract.Word
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RoomSnapshotTest {
    @Test fun largeStoredRecordCanBeReadAfterColdReopen() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "large-row-${UUID.randomUUID()}.db"
        val note = "合成内容".repeat(300_000)
        val word = Word(Id("large"), spelling = "sample", normalized = "sample", notes = note)
        RoomStateStore(WordPrismDatabase.open(context, name)).use { it.transaction { db -> db.words[word.id] = word } }
        try {
            RoomStateStore(WordPrismDatabase.open(context, name)).use {
                assertEquals(note, it.read().words.getValue(word.id).notes)
                it.transaction { db -> db.words.clear() }
                assertTrue(it.read().words.isEmpty())
            }
        } finally { context.deleteDatabase(WordPrismDatabase.file(context, name).absolutePath) }
    }
    @Test fun cachedSnapshotsKeepRollbackIsolationExternalWritesAndReopen() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "snapshot-${UUID.randomUUID()}.db"
        val first = RoomStateStore(WordPrismDatabase.open(context, name))
        val other = RoomStateStore(WordPrismDatabase.open(context, name))
        val word = Word(Id("word"), spelling = "sample", normalized = "sample")
        try {
            first.transaction { it.words[word.id] = word }
            val original = first.read()
            original.words.clear()
            assertEquals(word, first.read().words[word.id])
            try { first.transaction { it.words.clear(); error("rollback") } } catch (_: IllegalStateException) { }
            assertEquals(word, first.read().words[word.id])
            val revision = first.read().revision
            first.transaction { }
            assertEquals(revision, first.read().revision)
            other.transaction { it.words[word.id] = word.copy(notes = "external commit") }
            assertEquals("external commit", first.read().words.getValue(word.id).notes)
            val oldView = first.read()
            first.transaction { it.words.clear() }
            assertEquals(1, oldView.words.size)
            assertTrue(other.read().words.isEmpty())
            RoomStateStore(WordPrismDatabase.open(context, name)).use { reopened ->
                assertEquals(first.read(), reopened.read())
            }
        } finally {
            first.close(); other.close()
            context.deleteDatabase(WordPrismDatabase.file(context, name).absolutePath)
        }
    }
}
