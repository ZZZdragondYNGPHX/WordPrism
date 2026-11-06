package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class LearningPolishTest {
    private suspend fun recallOnly(h: Harness) {
        h.store.transaction { db ->
            db.policy = db.policy.copy(mix = mapOf(ActivityKind.RECALL to 100))
            db.policyVersions[db.policy.version] = db.policy
        }
    }

    @Test fun `unknown ungrouped word is taught before recall and survives reopening`() = runBlocking {
        val h = Harness()
        h.command(Command.SaveWords(listOf(WordSeed("linen", "亚麻布", "noun", examples = listOf("The shirt is made of linen.")))))
        recallOnly(h)
        val session = (h.command(Command.StartSession(includeIntroductions = true)) as View.SessionState).session.id
        val familiar = h.command(Command.NextActivity(session)) as View.SessionState
        h.answer(familiar, AnswerInput("", familiar = false))
        val lesson = h.command(Command.NextActivity(session)) as View.SessionState
        assertEquals(ActivityKind.CONTRAST, lesson.activity!!.kind)
        assertNull(lesson.activity!!.groupId)
        assertEquals("linen", lesson.activity!!.introduction!!.spelling)
        assertEquals(listOf("The shirt is made of linen."), lesson.activity!!.introduction!!.examples)
        val reopened = CoreEngine(h.store, h.credentials, h.memory, h.clock, h.provider)
        val restored = reopened.query(Query.GetSession(session)).value as View.SessionState
        assertEquals(lesson.activity, restored.activity)
        h.answer(restored, AnswerInput(""))
        val question = h.command(Command.NextActivity(session)) as View.SessionState
        assertEquals(ActivityKind.RECALL, question.activity!!.kind)
        val answer = h.answer(question, AnswerInput("linen"))
        assertTrue(answer.answer.input.answerRevealed)
        assertFalse(answer.answer.affectsSchedule)
        assertEquals(0, answer.schedule!!.reviews)
        validateDatabase(h.store.read(), h.memory)
    }

    @Test fun `missed target gets one assisted correction with another available context`() = runBlocking {
        val h = Harness()
        h.command(Command.SaveWords(listOf(WordSeed("linen", "亚麻布", "noun", examples = listOf(
            "The shirt is made of linen.", "We chose linen for the curtains.")))))
        recallOnly(h)
        val first = h.question()
        // Enable the app's guided flow after the fixture has supplied a scored first presentation.
        h.store.transaction { db -> db.sessions[first.session.id] = db.sessions.getValue(first.session.id).copy(includeIntroductions = true) }
        val wrong = h.answer(first, AnswerInput("cotton"))
        assertTrue(wrong.answer.affectsSchedule)
        val before = wrong.schedule
        h.command(Command.FinishSession(first.session.id, pause = true))
        val resumed = h.command(Command.StartSession(includeIntroductions = true)) as View.SessionState
        assertEquals(first.session.id, resumed.session.id)
        val correction = h.command(Command.NextActivity(first.session.id)) as View.SessionState
        assertNotEquals(first.activity!!.prompt, correction.activity!!.prompt)
        assertContains(correction.activity!!.prompt, "____")
        val corrected = h.answer(correction, AnswerInput("linen"))
        assertTrue(corrected.answer.input.answerRevealed)
        assertFalse(corrected.answer.affectsSchedule)
        assertEquals(before, corrected.schedule)
        val end = h.command(Command.NextActivity(first.session.id)) as View.SessionState
        assertEquals(SessionStatus.FINISHED, end.session.status)
        assertEquals(end.session.reinforcementTargets.toSet(), end.session.completedReinforcements)
        validateDatabase(h.store.read(), h.memory)
    }

    @Test fun `all acceptable choices survive feedback and an alternate is graded correct`() = runBlocking {
        val h = Harness(); h.seed()
        val shown = h.question()
        h.store.transaction { db ->
            val old = db.activities.getValue(shown.activity!!.id)
            val card = old.copy(kind = ActivityKind.CHOICE, choices = listOf(Choice("a", "first"), Choice("b", "second"), Choice("c", "wrong")),
                acceptedAnswers = listOf("a", "b"))
            db.activities[card.id] = card; db.activityVersions["${card.id.value}:${card.version}"] = card
        }
        val result = h.answer(shown, AnswerInput("", selectedChoiceId = "b"))
        assertEquals(Verdict.CORRECT, result.answer.verdict)
        assertEquals(listOf("a", "b"), result.correctChoiceIds)
        val restored = h.core.query(Query.GetFeedback(result.answer.id)).value as View.Feedbacks
        assertEquals(result.correctChoiceIds, restored.details!!.correctChoiceIds)
    }
}
