package dev.wordprism.memory

import dev.wordprism.contract.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class NativeKernelTest {
    @Test fun `packaged JNI performs bounded local fitting and evaluation on synthetic history`() {
        val kernel = NativeMemoryKernel()
        val histories = (0 until 160).flatMap { card ->
            val reviews = listOf(Review(3,0), Review(if (card % 5 == 0) 1 else 3,2), Review(3,4), Review(if (card % 7 == 0) 1 else 4,10))
            (2..4).map { History(reviews.take(it)) }
        }
        val fitted = kernel.fit(histories, 246810L)
        assertEquals(21, fitted.size); assertNotEquals(kernel.defaults(), fitted); kernel.validate(fitted)
        val metrics = kernel.evaluate(fitted, histories); assertTrue(metrics.log_loss.isFinite()); assertTrue(metrics.rmse_bins.isFinite())
        assertEquals(2.3065f, kernel.schedule(emptyList(), null, 0, Rating.GOOD, 0.9f).memory.stability)
    }
}
