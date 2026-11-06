package dev.wordprism.engine

import dev.wordprism.contract.*
import org.junit.jupiter.api.Test
import kotlin.system.measureNanoTime
import kotlin.test.*

class CommitSerializationTest {
    @Test fun `commit snapshot preserves changes deletions and no-op revision`() {
        val db = Database()
        db.words[Id("a")] = Word(Id("a"), spelling = "linen", normalized = "linen")
        val before = StateCodec.rows(db).associate { (it.bucket to it.key) to it.payload }
        assertNull(StateCodec.rowsForCommit(db, before))
        assertEquals(0, db.revision)
        db.words.clear()
        db.settings = db.settings.copy(budgetMinutes = 20)
        val rows = assertNotNull(StateCodec.rowsForCommit(db, before))
        assertEquals(1, db.revision)
        val restored = StateCodec.decode(rows)
        assertEquals(db, restored)
        assertTrue(restored.words.isEmpty())
        assertNull(StateCodec.rowsForCommit(db, rows.associate { (it.bucket to it.key) to it.payload }))
        assertEquals(1, db.revision)
    }

    @Test fun `large snapshot remains equivalent to previous commit serialization`() {
        val db = Database()
        repeat(2000) { index ->
            val id = Id("word-$index")
            db.words[id] = Word(id, spelling = "sample$index", normalized = "sample$index", notes = "synthetic note")
            db.senses[Id("sense-$index")] = Sense(Id("sense-$index"), id, meaning = "合成词义", examples = listOf("A synthetic sample$index sentence."))
        }
        val before = StateCodec.rows(db).associate { (it.bucket to it.key) to it.payload }
        db.sequence++
        fun previous(): List<StateCodec.Row> {
            val old = StateCodec.rows(db).associate { (it.bucket to it.key) to it.payload }
            check(old != before)
            db.revision++
            return StateCodec.rows(db)
        }
        repeat(5) { previous(); StateCodec.rowsForCommit(db, before) }
        val oldTimes = List(15) { measureNanoTime { previous() } }.sorted()
        val newTimes = List(15) { measureNanoTime { StateCodec.rowsForCommit(db, before) } }.sorted()
        println("Synthetic JVM serialization, 2000 words + senses, median of 15: previous=${oldTimes[7] / 1_000_000.0} ms, single-snapshot=${newTimes[7] / 1_000_000.0} ms. Not Android frame-time evidence.")
        db.revision = 0
        val expected = previous()
        db.revision = 0
        assertEquals(expected, StateCodec.rowsForCommit(db, before))
    }
}
