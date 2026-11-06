package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class DailyPlanningTest {
    @Test fun `queued work can use the daily budget without exceeding new or review limits`() = runBlocking {
        val h = Harness()
        h.command(Command.SaveWords((1..30).map { WordSeed("word$it", "meaning $it") }))
        h.store.transaction { db ->
            db.settings = db.settings.copy(budgetMinutes = 21)
            db.policy = db.policy.copy(plannedMinutes = 10)
            db.policyVersions[db.policy.version] = db.policy
            // Enough due reviews to consume the old ten-minute plan, plus untouched new words.
            val reviewTargets = db.targets.values.filter { it.ability == Ability.RECALL }.take(12)
            val session = db.startSession(h.clock.millis(), false)
            val yesterday = db.day(h.clock.millis() - DAY_MS)
            for (target in reviewTargets) {
                val activity = db.localActivity(target, ActivityKind.RECALL, h.clock.millis())
                val answer = Answer(id(), ++db.sequence, session.session.id, activity.id, activity.version, target.id,
                    input = AnswerInput("answer"), atMs = h.clock.millis() - DAY_MS, verdict = Verdict.CORRECT,
                    gradeSource = GradeSource.OBJECTIVE, rating = Rating.GOOD, affectsSchedule = true,
                    policyVersion = db.policy.version, delayed = true, newContext = true, dayId = yesterday.id)
                db.answers[answer.id] = answer
                db.schedules[target.id] = db.schedules.getValue(target.id).copy(reviews = 1, dueAtMs = 0)
            }
            db.days.replaceAll { _, day -> day.copy(planId = null) }
        }
        val day = h.core.query(Query.GetLearningDay()).value as View.Day
        val plan = day.plan!!
        assertEquals(12, plan.reviewCount)
        assertTrue(plan.newCount > 0)
        assertTrue(plan.estimatedMs > 10 * 60_000)
        assertTrue(plan.estimatedMs <= 21 * 60_000)
        assertTrue(plan.newCount <= 12)
        assertTrue(plan.deferredNewWordCount > 0)
        assertEquals(0, plan.deferredReviewCount)
    }

    @Test fun `yesterdays paused queue does not override todays plan or erase saved answers`() = runBlocking {
        val h = Harness(); h.seed(); val answered = h.answer()
        val oldSession = (h.command(Command.NextActivity(answered.answer.sessionId)) as View.SessionState).session
        h.command(Command.FinishSession(oldSession.id, pause = true))
        val before = h.store.read()
        h.clock.advance(DAY_MS)
        val day = h.core.query(Query.GetLearningDay()).value as View.Day
        assertNull(day.summary.openSessionId)
        val started = h.command(Command.StartSession()) as View.SessionState
        assertNotEquals(oldSession.id, started.session.id)
        assertEquals(day.day.id, started.session.dayId)
        assertEquals(day.plan!!.targetIds.take(before.policy.sessionTargetLimit), started.session.targetIds)
        val after = h.store.read()
        assertEquals(SessionStatus.FINISHED, after.sessions.getValue(oldSession.id).status)
        assertEquals(before.answers, after.answers)
        assertEquals(before.schedules, after.schedules)
        assertEquals(before.plans.getValue(oldSession.planId), after.plans.getValue(oldSession.planId))
    }

    @Test fun `same day paused question resumes unchanged`() = runBlocking {
        val h = Harness(); h.seed(); val shown = h.question()
        h.command(Command.FinishSession(shown.session.id, pause = true))
        val day = h.core.query(Query.GetLearningDay()).value as View.Day
        assertEquals(shown.session.id, day.summary.openSessionId)
        val resumed = h.command(Command.StartSession()) as View.SessionState
        assertEquals(shown.session.id, resumed.session.id)
        assertEquals(shown.session.currentPresentationId, resumed.session.currentPresentationId)
        assertEquals(shown.activity, resumed.activity)
    }

    @Test fun `current legacy plan upgrades once and historical plan remains intact`() = runBlocking {
        val h = Harness(); h.seed()
        val first = h.core.query(Query.GetLearningDay()).value as View.Day
        h.store.transaction { db -> db.plans[first.plan!!.id] = first.plan!!.copy(plannerVersion = 0) }
        val updated = h.core.query(Query.GetLearningDay()).value as View.Day
        assertNotEquals(first.plan!!.id, updated.plan!!.id)
        assertEquals(PLANNER_VERSION, updated.plan!!.plannerVersion)
        assertEquals(0, h.store.read().plans.getValue(first.plan!!.id).plannerVersion)
        assertEquals(updated, h.core.query(Query.GetLearningDay()).value)
    }

    @Test fun `disabled abilities are not counted as deferred workload`() = runBlocking {
        val h = Harness(); h.seed()
        h.command(Command.ApplyPolicy(PolicyPatch(mapOf("mix" to json("""{"RECALL":100}""")), "recall only"), 1))
        val plan = (h.core.query(Query.GetLearningDay()).value as View.Day).plan!!
        assertEquals(2, plan.targetIds.size)
        assertEquals(0, plan.deferredCount)
    }
}
