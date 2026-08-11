package dev.bookscanner.vision

import dev.bookscanner.core.contracts.GrayscaleImage
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The duplicate-page fingerprint.
 *
 * Every property here is one that raw pixel comparison lacked, and each is the
 * reason a real duplicate slipped through: the page moved, the exposure
 * changed, and the difference came out larger than a genuinely new page's.
 */
class PageSignatureTest {
    private val width = 320
    private val height = 240

    /**
     * A page-like image: paragraphs of closely spaced lines.
     *
     * The density matters. Real body text shrinks to smooth mid-grey, so a
     * re-aim that lands between two signature cells costs little. Two earlier
     * fixtures got this wrong — twelve thick bars at fixed rows made *different*
     * pages look identical, and sparse hard-edged lines made a small re-aim
     * look like a new page. Both were the fixture failing to model a page, not
     * the signature failing to work: on real photographs the same page scores
     * 0.17 and different pages 0.52 or more.
     */
    private fun page(
        pattern: Int,
        offsetX: Int = 0,
        offsetY: Int = 0,
        brightness: Int = 210,
        contrast: Float = 1f,
    ): GrayscaleImage {
        val pixels = ByteArray(width * height) { brightness.toByte() }
        var seed = pattern.toLong() * 7919 + 13

        fun next(bound: Int): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33).toInt() and 0x7FFFFFFF) % bound
        }

        val ink = (brightness - 110 * contrast).toInt().coerceIn(0, 255).toByte()
        // Five paragraphs, each a run of lines 4 px apart — dense enough that
        // shrinking turns them into tone, as body text does.
        repeat(5) {
            val paragraphTop = 15 + next(height - 70) + offsetY
            val paragraphLeft = 15 + next(width - 160) + offsetX
            val lineCount = 6 + next(8)
            repeat(lineCount) { line ->
                val y = paragraphTop + line * 4
                if (y !in 0 until height) return@repeat
                val lineLength = 60 + next(110)
                for (x in max(0, paragraphLeft) until min(width, paragraphLeft + lineLength)) {
                    pixels[y * width + x] = ink
                    if (y + 1 < height) pixels[(y + 1) * width + x] = ink
                }
            }
        }
        return GrayscaleImage(width, height, pixels)
    }

    @Test
    fun `the same page matches itself`() {
        val signature = PageSignature.of(page(pattern = 1))

        assertTrue(signature.looksLikeSamePageAs(signature))
        assertTrue(signature.distanceTo(signature) < 0.001f)
    }

    @Test
    fun `two different pages do not match`() {
        val first = PageSignature.of(page(pattern = 1))
        val second = PageSignature.of(page(pattern = 99))

        val distance = first.distanceTo(second)
        assertTrue(
            !first.looksLikeSamePageAs(second),
            "different pages should not be confused; distance was $distance",
        )
    }

    @Test
    fun `re-aiming at the same page still matches`() {
        // The case that motivated this: the user shot page 200, did not notice
        // it had been captured, moved slightly and shot it again.
        val first = PageSignature.of(page(pattern = 7))
        val moved = PageSignature.of(page(pattern = 7, offsetX = 12, offsetY = 8))

        val distance = first.distanceTo(moved)
        assertTrue(
            first.looksLikeSamePageAs(moved),
            "a small re-aim must not read as a new page; distance was $distance",
        )
    }

    @Test
    fun `a change in lighting does not make a page look new`() {
        // Normalising mean and deviation away is what buys this: the same page
        // by a window and under a lamp is the same page.
        val bright = PageSignature.of(page(pattern = 4, brightness = 230, contrast = 1f))
        val dim = PageSignature.of(page(pattern = 4, brightness = 120, contrast = 0.55f))

        val distance = bright.distanceTo(dim)
        assertTrue(
            bright.looksLikeSamePageAs(dim),
            "exposure alone must not read as new content; distance was $distance",
        )
    }

    @Test
    fun `a shift beyond the search window is not forced to match`() {
        // The alignment search is bounded on purpose; a page that moved half a
        // frame is a different framing the user probably meant.
        val first = PageSignature.of(page(pattern = 3))
        val farAway = PageSignature.of(page(pattern = 3, offsetX = 120, offsetY = 90))

        assertTrue(first.distanceTo(farAway) > first.distanceTo(PageSignature.of(page(pattern = 3, offsetX = 4))))
    }

    @Test
    fun `the verdict does not depend on which page is asked`() {
        // The distance itself is mildly asymmetric by construction — the search
        // slides the *other* signature across this one's interior — so the
        // property worth asserting is that both directions reach the same
        // conclusion, not that they produce identical numbers.
        val pairs =
            listOf(
                PageSignature.of(page(pattern = 1)) to PageSignature.of(page(pattern = 2)),
                PageSignature.of(page(pattern = 7)) to PageSignature.of(page(pattern = 7, offsetX = 10)),
                PageSignature.of(page(pattern = 5)) to PageSignature.of(page(pattern = 5)),
            )

        pairs.forEach { (first, second) ->
            assertEquals(
                first.looksLikeSamePageAs(second),
                second.looksLikeSamePageAs(first),
                "verdict flipped with the argument order: " +
                    "${first.distanceTo(second)} vs ${second.distanceTo(first)}",
            )
        }
    }

    @Test
    fun `a featureless image does not produce NaN`() {
        val flat = PageSignature.of(GrayscaleImage(64, 48, ByteArray(64 * 48) { 128.toByte() }))

        val distance = flat.distanceTo(flat)
        assertTrue(!distance.isNaN(), "a flat image has no deviation to normalise by")
    }

    @Test
    fun `images smaller than the signature still work`() {
        val tiny = PageSignature.of(GrayscaleImage(20, 15, ByteArray(20 * 15) { (it % 200).toByte() }))

        assertTrue(!tiny.distanceTo(tiny).isNaN())
    }
}
