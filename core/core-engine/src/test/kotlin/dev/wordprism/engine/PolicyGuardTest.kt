package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class PolicyGuardTest {
    @Test fun `hint exposure persists even if a client omits the flag`() = runBlocking {
        val h = Harness(); h.seed(); val card = h.question()
        val activity = h.store.read().activities[card.activity!!.id]!!
        h.command(Command.RequestHint(card.session.id, card.session.currentPresentationId!!))
        val answer = h.answer(card, AnswerInput(activity.acceptedAnswers.first()))
        assertTrue(answer.answer.input.hintUsed); assertFalse(answer.answer.affectsSchedule)
        val duplicate = h.answer(card, AnswerInput(activity.acceptedAnswers.first()))
        assertEquals(answer.answer.id, duplicate.answer.id)
    }
    @Test fun `AI maintenance and policy adjustment share traceable reversible operations`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer(); val word = h.store.read().words.values.first()
        h.provider.response = { ProviderResponse(json("""{"patch":{"values":{"newTargetLimit":2},"reason":"observed load"},"actions":[{"type":"update_word","word":{"id":"${word.id.value}","version":1},"notes":"AI note","tags":[]}]}""")) }
        val op = h.finishDay()!!; h.core.tasks.run(op.id)
        val applied = h.store.read(); assertEquals(2, applied.policy.newTargetLimit); assertEquals("AI note", applied.words[word.id]!!.notes)
        h.answer(); val answers = h.store.read().answers
        h.command(Command.UndoOperation(op.id)); assertEquals(12, h.store.read().policy.newTargetLimit)
        assertEquals(word.notes, h.store.read().words[word.id]!!.notes); assertEquals(answers, h.store.read().answers)
    }
    @Test fun `automatic adjustments cannot change locked preferences or accept extra words`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer()
        val settings = h.store.read().settings; h.command(Command.SetSettings(settings.copy(lockedCapabilities = setOf("newTargetLimit")), settings.version))
        h.provider.response = { ProviderResponse(json("""{"patch":{"values":{"newTargetLimit":0},"reason":"try override"}}""")) }
        val task = h.finishDay()!!; h.core.tasks.run(task.id)
        assertEquals(ErrorCode.DISABLED, h.store.read().operations[task.id]!!.fault?.code); assertEquals(12, h.store.read().policy.newTargetLimit)
    }
    @Test fun `delayed auto execution prevents an extra automatic task on execution date`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer(); val old = h.finishDay()!!
        h.clock.advance(DAY_MS); h.core.tasks.run(old.id)
        h.answer(); val next = h.finishDay()!!; h.core.tasks.run(next.id)
        assertEquals(1, h.provider.requests.size); assertEquals(OperationStatus.SUPERSEDED, h.store.read().operations[next.id]!!.status)
    }
    @Test fun `changing time zone does not reset an occupied daily slot`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer(); val op = h.finishDay()!!; h.core.tasks.run(op.id)
        val settings = h.store.read().settings; h.command(Command.SetSettings(settings.copy(zoneId = "Pacific/Kiritimati"), settings.version))
        h.answer(); h.finishDay(); h.core.tasks.runPending(true)
        assertEquals(1, h.provider.requests.size); assertEquals(1, h.store.read().days.size)
    }
    @Test fun `plan stays inside daily budget and retains deferred due work`() = runBlocking {
        val h = Harness(); h.seed()
        val settings = h.store.read().settings; h.command(Command.SetSettings(settings.copy(budgetMinutes = 1), settings.version))
        val day = (h.core.query(Query.GetLearningDay()).value as View.Day)
        assertTrue(day.plan!!.estimatedMs <= 60_000); assertTrue(day.plan!!.deferredCount > 0)
    }
}
