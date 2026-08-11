package dev.bookscanner.vision

import dev.bookscanner.core.contracts.EngineId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Detection quality, as a number, in CI.
 *
 * This is the reason [ContourPageDetector] is a hand-written pure-JVM pipeline
 * rather than a CV dependency: an OpenCV detector could not run here at all
 * (ADR-0008), so its quality could only ever be asserted, never measured.
 *
 * Errors are printed as `MEASURE` lines so every run records the numbers and
 * a regression shows up as a moving figure rather than only a pass/fail.
 */
class ContourPageDetectorTest {
    private val detector = ContourPageDetector()

    @Test
    fun `finds a rectangular page on a plain background`() {
        val image = SyntheticPages.page()

        val detection = detector.detect(image)

        val boundary = assertNotNull(detection.boundary, "should find an obvious page")
        val error = cornerError(SyntheticPages.defaultCorners, boundary)
        println("MEASURE detect-plain cornerError=${error.asError()} confidence=${detection.confidence}")
        assertTrue(error < 0.05f, "corner error ${error.asError()} exceeds 0.05 of the frame")
    }

    @Test
    fun `finds a page seen from an angle`() {
        // The case that motivates perspective correction: opposite edges are
        // not parallel, so a rectangle-only detector would have to fail.
        val image = SyntheticPages.page(corners = SyntheticPages.perspectiveCorners)

        val detection = detector.detect(image)

        val boundary = assertNotNull(detection.boundary, "should find a perspective-distorted page")
        val error = cornerError(SyntheticPages.perspectiveCorners, boundary)
        println("MEASURE detect-perspective cornerError=${error.asError()} confidence=${detection.confidence}")
        assertTrue(error < 0.08f, "corner error ${error.asError()} exceeds 0.08 of the frame")
    }

    @Test
    fun `survives sensor noise`() {
        val image = SyntheticPages.page(noise = 12)

        val detection = detector.detect(image)

        val boundary = assertNotNull(detection.boundary, "noise should not defeat detection")
        val error = cornerError(SyntheticPages.defaultCorners, boundary)
        println("MEASURE detect-noisy cornerError=${error.asError()} confidence=${detection.confidence}")
        assertTrue(error < 0.06f, "corner error ${error.asError()} exceeds 0.06 of the frame")
    }

    @Test
    fun `survives low contrast between page and background`() {
        // A white page on a pale desk: the gradient is weak everywhere, which
        // is exactly why the threshold is chosen by Otsu rather than fixed.
        val image = SyntheticPages.page(pageLuminance = 200, backgroundLuminance = 165)

        val detection = detector.detect(image)

        val boundary = assertNotNull(detection.boundary, "low contrast should still be detectable")
        val error = cornerError(SyntheticPages.defaultCorners, boundary)
        println("MEASURE detect-low-contrast cornerError=${error.asError()} confidence=${detection.confidence}")
        assertTrue(error < 0.08f, "corner error ${error.asError()} exceeds 0.08 of the frame")
    }

    @Test
    fun `is not fooled by straight-edged clutter behind the page`() {
        val image = SyntheticPages.page(backgroundClutter = true)

        val detection = detector.detect(image)

        val boundary = assertNotNull(detection.boundary, "clutter should not prevent detection")
        val error = cornerError(SyntheticPages.defaultCorners, boundary)
        println("MEASURE detect-cluttered cornerError=${error.asError()} confidence=${detection.confidence}")
        assertTrue(error < 0.10f, "clutter pulled the boundary off by ${error.asError()}")
    }

    @Test
    fun `reports not-found on a blank frame rather than inventing a page`() {
        val detection = detector.detect(SyntheticPages.blank())

        // A detector that always answers is worse than one that admits defeat:
        // a wrong boundary applied automatically destroys the framing.
        assertTrue(!detection.found, "a featureless frame has no page, got ${detection.boundary}")
        assertEquals(0f, detection.confidence)
    }

    @Test
    fun `confidence is higher for a clean page than a hard one`() {
        val clean = detector.detect(SyntheticPages.page())
        val hard = detector.detect(SyntheticPages.page(pageLuminance = 200, backgroundLuminance = 165, noise = 15))

        println("MEASURE confidence clean=${clean.confidence} hard=${hard.confidence}")
        assertTrue(
            clean.confidence >= hard.confidence,
            "confidence should track difficulty: clean=${clean.confidence} hard=${hard.confidence}",
        )
    }

    @Test
    fun `declares itself as the from-scratch engine`() {
        // The contract's whole purpose is engine substitution; a detector that
        // misreports its family would make a benchmark meaningless.
        assertEquals(EngineId.FROM_SCRATCH, detector.engine)
    }

    @Test
    fun `detection is deterministic`() {
        val image = SyntheticPages.page(noise = 10)

        val first = detector.detect(image)
        val second = detector.detect(image)

        assertEquals(first.boundary, second.boundary)
        assertEquals(first.confidence, second.confidence)
    }

    @Test
    fun `works at capture resolution without running out of time`() {
        // Real captures are 4080x3072; the detector downscales first, and this
        // asserts that the downscale actually bounds the cost.
        val image = SyntheticPages.page(width = 2040, height = 1536)

        val start = System.nanoTime()
        val detection = detector.detect(image)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        println("MEASURE detect-latency size=2040x1536 ms=$elapsedMs")
        assertTrue(detection.found, "should find the page at capture-like resolution")
        assertTrue(elapsedMs < 4_000, "detection took ${elapsedMs}ms, which would be felt in the capture loop")
    }
}
