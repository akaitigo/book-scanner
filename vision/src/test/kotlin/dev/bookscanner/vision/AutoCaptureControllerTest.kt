package dev.bookscanner.vision

import dev.bookscanner.core.contracts.GrayscaleImage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The auto-capture state machine.
 *
 * Tested here rather than on a device because the whole point of keeping it in
 * pure Kotlin is that "turn the page, settle, hold" is a sequence, and a
 * sequence is exactly what a camera makes hard to reproduce twice.
 */
class AutoCaptureControllerTest {
    private val width = 40
    private val height = 30

    /** A frame with uniform luminance, plus optional per-pixel jitter. */
    private fun frame(
        level: Int,
        jitter: Int = 0,
        seed: Long = 1,
    ): GrayscaleImage {
        var state = seed or 1L
        val pixels =
            ByteArray(width * height) {
                state = state * 6364136223846793005L + 1442695040888963407L
                val noise = if (jitter == 0) 0 else ((state ushr 33).toInt() % (jitter * 2 + 1)) - jitter
                (level + noise).coerceIn(0, 255).toByte()
            }
        return GrayscaleImage(width, height, pixels)
    }

    /** Feeds still frames until it fires, or gives up. */
    private fun holdStill(
        controller: AutoCaptureController,
        startMillis: Long,
        level: Int,
        steps: Int = 20,
        stepMillis: Long = 100,
    ): Pair<AutoCaptureController.Decision, Long> {
        var now = startMillis
        var decision =
            AutoCaptureController.Decision(AutoCaptureController.Status.SEARCHING, shouldCapture = false)
        repeat(steps) {
            decision = controller.onFrame(frame(level, jitter = 1, seed = 1), now)
            if (decision.shouldCapture) return decision to now
            now += stepMillis
        }
        return decision to now
    }

    @Test
    fun `a held page fires the shutter without any input`() {
        val controller = AutoCaptureController()

        val (decision, firedAt) = holdStill(controller, startMillis = 0, level = 200)

        assertTrue(decision.shouldCapture, "holding still should be enough to capture")
        // 700 ms of stillness, plus the frame that establishes a baseline.
        assertTrue(firedAt in 700..900, "fired at ${firedAt}ms, expected shortly after the 700ms hold")
    }

    @Test
    fun `movement prevents capture no matter how long it goes on`() {
        val controller = AutoCaptureController()
        var now = 0L

        repeat(30) { step ->
            // Alternating brightness: the scene never settles.
            val decision = controller.onFrame(frame(if (step % 2 == 0) 60 else 200), now)
            assertTrue(!decision.shouldCapture, "must not fire while the frame is changing")
            now += 100
        }
    }

    @Test
    fun `it does not fire twice at the same page`() {
        val controller = AutoCaptureController()
        val (first, firedAt) = holdStill(controller, startMillis = 0, level = 200)
        assertTrue(first.shouldCapture)

        // Keep holding the very same page well past the cooldown.
        var now = firedAt + 2_000
        repeat(30) {
            val decision = controller.onFrame(frame(200, jitter = 1, seed = 1), now)
            assertTrue(!decision.shouldCapture, "the same page must not be captured again")
            assertEquals(AutoCaptureController.Status.WAITING_FOR_NEW_PAGE, decision.status)
            now += 100
        }
    }

    @Test
    fun `turning the page arms it again`() {
        val controller = AutoCaptureController()
        val (_, firedAt) = holdStill(controller, startMillis = 0, level = 200)

        // The page turns: motion, then a visibly different scene held still.
        var now = firedAt + 1_500
        repeat(3) {
            controller.onFrame(frame(if (it % 2 == 0) 60 else 150), now)
            now += 100
        }
        val (second, _) = holdStill(controller, startMillis = now, level = 120)

        assertTrue(second.shouldCapture, "a different page held still should capture again")
    }

    @Test
    fun `the cooldown blocks capture immediately after firing`() {
        val controller = AutoCaptureController()
        val (_, firedAt) = holdStill(controller, startMillis = 0, level = 200)

        // Still frames during the cooldown: a shutter is running, and analysing
        // its output as if it were a new page would double-capture.
        var now = firedAt + 100
        repeat(5) {
            val decision = controller.onFrame(frame(90, jitter = 1, seed = 5), now)
            assertTrue(!decision.shouldCapture, "must stay quiet during the cooldown")
            now += 100
        }
    }

    @Test
    fun `a detected page shortens the hold, but its absence never blocks capture`() {
        val withBoundary = AutoCaptureController()
        val withoutBoundary = AutoCaptureController()

        var now = 0L
        var detectedAt = -1L
        repeat(20) {
            val decision =
                withBoundary.onFrame(frame(200, jitter = 1, seed = 1), now, boundary = SyntheticPages.defaultCorners)
            if (decision.shouldCapture && detectedAt < 0) detectedAt = now
            now += 100
        }
        val (plain, plainAt) = holdStill(withoutBoundary, startMillis = 0, level = 200)

        assertTrue(detectedAt >= 0, "a detected page should still capture")
        assertTrue(plain.shouldCapture, "an undetected page must capture too — detection is advisory")
        assertTrue(
            detectedAt < plainAt,
            "detection should shorten the wait: detected=${detectedAt}ms plain=${plainAt}ms",
        )
    }

    @Test
    fun `hold progress climbs toward the trigger`() {
        val controller = AutoCaptureController()
        controller.onFrame(frame(200, jitter = 1, seed = 1), 0)

        val early = controller.onFrame(frame(200, jitter = 1, seed = 1), 200)
        val late = controller.onFrame(frame(200, jitter = 1, seed = 1), 600)

        assertEquals(AutoCaptureController.Status.HOLDING, early.status)
        assertTrue(
            late.holdProgress > early.holdProgress,
            "progress should advance: ${early.holdProgress} -> ${late.holdProgress}",
        )
        assertTrue(late.holdProgress <= 1f)
    }

    @Test
    fun `sensor noise alone does not read as movement`() {
        val controller = AutoCaptureController()

        // Same scene, different noise each frame — a real static camera.
        var now = 0L
        var fired = false
        repeat(20) { step ->
            val decision = controller.onFrame(frame(180, jitter = 2, seed = step.toLong() + 1), now)
            if (decision.shouldCapture) fired = true
            now += 100
        }

        assertTrue(fired, "noise must not be mistaken for the user moving the phone")
    }

    @Test
    fun `resetting forgets the previous page`() {
        val controller = AutoCaptureController()
        val (_, firedAt) = holdStill(controller, startMillis = 0, level = 200)

        controller.reset()
        val (again, _) = holdStill(controller, startMillis = firedAt + 100, level = 200)

        assertTrue(again.shouldCapture, "after a reset the same scene is new again")
    }

    @Test
    fun `a changed frame size is handled rather than crashing`() {
        val controller = AutoCaptureController()
        controller.onFrame(frame(200), 0)

        val decision = controller.onFrame(GrayscaleImage(10, 10, ByteArray(100) { 200.toByte() }), 100)

        assertTrue(!decision.shouldCapture)
    }
}
