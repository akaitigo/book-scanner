package dev.bookscanner.vision

import dev.bookscanner.core.contracts.GrayscaleImage
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The image primitives, tested one at a time.
 *
 * The end-to-end detector tests say whether a page was found; these say which
 * stage is wrong when it was not. Both matter — a six-stage pipeline that only
 * has an end-to-end test is a pipeline you debug by guessing.
 */
class ImageOpsTest {
    private fun image(
        width: Int,
        height: Int,
        fill: (x: Int, y: Int) -> Int,
    ): GrayscaleImage {
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = fill(x, y).coerceIn(0, 255).toByte()
            }
        }
        return GrayscaleImage(width, height, pixels)
    }

    // ---- GrayscaleImage ----

    @Test
    fun `pixel count must match the dimensions`() {
        assertFailsWith<IllegalArgumentException> { GrayscaleImage(4, 4, ByteArray(15)) }
    }

    @Test
    fun `coordinates outside the image clamp to the edge`() {
        val source = image(3, 3) { x, _ -> x * 100 }

        // Clamping rather than wrapping or throwing: convolution kernels run
        // off the edge on every border pixel, and wrapping would fabricate an
        // edge between opposite sides of the image.
        assertEquals(source.luminanceAt(0, 0), source.luminanceAt(-5, -5))
        assertEquals(source.luminanceAt(2, 2), source.luminanceAt(99, 99))
    }

    // ---- downscale ----

    @Test
    fun `downscaling averages rather than samples`() {
        // A checkerboard averages to mid-grey; point sampling would return
        // pure black or white and alias the pattern into the result.
        val checker = image(4, 4) { x, y -> if ((x + y) % 2 == 0) 0 else 255 }

        val small = ImageOps.downscale(checker, 2)

        assertEquals(2, small.width)
        assertEquals(2, small.height)
        small.pixels.forEach { byte ->
            val value = byte.toInt() and 0xFF
            assertTrue(abs(value - 127) <= 1, "expected ~127 from averaging, got $value")
        }
    }

    @Test
    fun `a factor of one returns the same image`() {
        val source = image(3, 3) { x, y -> x + y }
        assertTrue(ImageOps.downscale(source, 1) === source)
    }

    @Test
    fun `scale factor targets the longest edge`() {
        assertEquals(1, ImageOps.scaleFactorFor(image(400, 300) { _, _ -> 0 }, 600))
        assertEquals(6, ImageOps.scaleFactorFor(image(4080, 3072) { _, _ -> 0 }, 600))
    }

    // ---- gaussian ----

    @Test
    fun `the gaussian kernel sums to one and is symmetric`() {
        val kernel = ImageOps.gaussianKernel(3)

        assertEquals(7, kernel.size)
        assertEquals(1f, kernel.sum(), 1e-4f, "an unnormalized kernel would change image brightness")
        for (i in 0..2) {
            assertEquals(kernel[i], kernel[kernel.size - 1 - i], 1e-6f, "kernel must be symmetric")
        }
        assertTrue(kernel[3] > kernel[0], "the centre must carry the most weight")
    }

    @Test
    fun `blurring a flat image changes nothing`() {
        val flat = image(8, 8) { _, _ -> 120 }

        val blurred = ImageOps.gaussianBlur(flat, 2)

        blurred.pixels.forEach { assertEquals(120, it.toInt() and 0xFF) }
    }

    @Test
    fun `blurring softens a step edge over several pixels`() {
        val step = image(16, 4) { x, _ -> if (x < 8) 0 else 255 }

        val blurred = ImageOps.gaussianBlur(step, 2)

        val across = (5..10).map { blurred.luminanceAt(it, 2) }
        assertTrue(across.zipWithNext().all { (a, b) -> b >= a }, "the ramp should be monotonic: $across")
        assertTrue(across.any { it in 40..215 }, "expected intermediate values, got $across")
    }

    // ---- sobel ----

    @Test
    fun `sobel finds a vertical edge and reports a horizontal gradient`() {
        val step = image(16, 8) { x, _ -> if (x < 8) 0 else 255 }

        val gradients = ImageOps.sobel(step)

        val onEdge = gradients.magnitudeAt(8, 4)
        val insideFlat = gradients.magnitudeAt(2, 4)
        assertTrue(onEdge > insideFlat * 10, "edge=$onEdge flat=$insideFlat")

        // A vertical edge has a horizontal gradient: direction near 0 radians.
        val direction = abs(gradients.directionAt(8, 4))
        assertTrue(direction < 0.2f || abs(direction - Math.PI.toFloat()) < 0.2f, "direction=$direction")
    }

    @Test
    fun `sobel is quiet on a flat image`() {
        val flat = image(8, 8) { _, _ -> 90 }

        val gradients = ImageOps.sobel(flat)

        assertTrue(gradients.magnitude.all { it == 0f }, "a flat image has no edges")
    }

    // ---- non-maximum suppression ----

    @Test
    fun `non-maximum suppression thins a wide ridge`() {
        // A blurred edge is several pixels wide; Hough would otherwise get
        // several votes per edge column and smear its peak.
        val blurred = ImageOps.gaussianBlur(image(24, 8) { x, _ -> if (x < 12) 0 else 255 }, 3)
        val gradients = ImageOps.sobel(blurred)

        val before = gradients.magnitude.count { it > 1f }
        val after = gradients.nonMaximumSuppressed().magnitude.count { it > 1f }

        assertTrue(after < before, "suppression should remove pixels: before=$before after=$after")
        assertTrue(after > 0, "it must not remove the edge entirely")
    }

    // ---- otsu ----

    @Test
    fun `otsu separates two clear modes`() {
        val values = FloatArray(200) { if (it < 100) 10f else 200f }

        val threshold = ImageOps.otsuThreshold(values)

        assertTrue(threshold > 10f && threshold < 200f, "threshold=$threshold should fall between the modes")
    }

    @Test
    fun `otsu ignores zero-magnitude pixels`() {
        // The regression this exists for: after suppression most pixels are
        // exactly zero, and counting them dragged the split down to zero — so
        // every pixel became an "edge" and the detector failed on the cleanest
        // images while working on noisy ones.
        // Two non-zero modes (real edges and texture) buried in zeros, which
        // is what a suppressed gradient field actually looks like.
        val mostlyZero =
            FloatArray(10_000) {
                when {
                    it < 100 -> 500f
                    it < 300 -> 80f
                    else -> 0f
                }
            }

        val threshold = ImageOps.otsuThreshold(mostlyZero)

        assertTrue(
            threshold > 80f && threshold < 500f,
            "the split should land between the two real modes, not be dragged to 0 by the zeros; got $threshold",
        )
    }

    @Test
    fun `otsu returns zero when there is nothing to separate`() {
        assertEquals(0f, ImageOps.otsuThreshold(FloatArray(0)))
        assertEquals(0f, ImageOps.otsuThreshold(FloatArray(50)))
        // A single non-zero level has no split either; the detector floors the
        // threshold rather than relying on Otsu to invent one.
        assertEquals(0f, ImageOps.otsuThreshold(FloatArray(50) { if (it < 10) 500f else 0f }))
    }

    // ---- hysteresis ----

    @Test
    fun `hysteresis keeps weak pixels connected to strong ones`() {
        // One strong pixel with a weak tail: a single threshold either loses
        // the tail or admits the isolated noise below.
        val magnitude = FloatArray(25)
        magnitude[12] = 100f // strong, centre
        magnitude[13] = 50f // weak, adjacent
        magnitude[14] = 50f // weak, adjacent to the above
        magnitude[0] = 50f // weak, isolated
        val field = GradientField(5, 5, magnitude, FloatArray(25))

        val points = field.edgePointsWithHysteresis(high = 90f, low = 40f)
        val kept = points.map { it.y * 5 + it.x }.toSet()

        assertTrue(12 in kept, "the strong pixel must survive")
        assertTrue(13 in kept && 14 in kept, "weak pixels connected to it must survive")
        assertTrue(0 !in kept, "an isolated weak pixel is noise and must not")
    }

    @Test
    fun `hysteresis with nothing above the high threshold keeps nothing`() {
        val field = GradientField(3, 3, FloatArray(9) { 10f }, FloatArray(9))

        assertTrue(field.edgePointsWithHysteresis(high = 100f, low = 5f).isEmpty())
    }
}
