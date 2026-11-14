package dev.wordprism.engine

import dev.wordprism.contract.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class DailyAnalysisRecoveryTest {
    @Test fun `successful manual analysis covers failed daily analysis without erasing failure`() = runBlocking {
        val h = Harness(); h.configure(); h.seed(); h.answer()
        val automatic = assertNotNull(h.finishDay())
        h.provider.response = { throw ProviderException(Fault(ErrorCode.OUTCOME_UNKNOWN, "interrupted")) }
        h.core.tasks.run(automatic.id)
        assertEquals("failed", (h.core.query(Query.GetLearningDay()).value as View.Day).summary.analysisState)
        val manual = (h.command(Command.AnalyzeLearning(force = true)) as View.OperationResult).operation
        h.provider.response = { ProviderResponse(json("""{"patch":{"values":{},"reason":"继续按当前安排学习","evidenceIds":[]},"actions":[]}""")) }
        h.core.tasks.run(manual.id)
        val day = h.core.query(Query.GetLearningDay()).value as View.Day
        assertEquals("done", day.summary.analysisState)
        assertEquals(manual.id, day.automatic?.id)
        assertEquals(automatic.id, day.day.automaticOperationId)
        assertEquals(OperationStatus.FAILED, h.store.read().operations.getValue(automatic.id).status)
        assertEquals(2, h.provider.requests.size)
    }
}
