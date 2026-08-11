package dev.bookscanner.vision

import dev.bookscanner.core.contracts.GrayscaleImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A small, lighting-independent fingerprint of a page, for recognising that
 * the same page has been photographed twice.
 *
 * Raw pixel comparison cannot do this. Re-aiming at the same page shifts and
 * rescales it enough that the per-pixel difference is as large as a different
 * page's — measured at 22.7 on a real pair of duplicates, against a threshold
 * of 8 that was supposed to catch them. Three things fix that:
 *
 * - **shrink**, so text becomes tone and small misalignments stop mattering;
 * - **normalise** mean and deviation away, so a change in lighting or exposure
 *   between two shots of the same page does not register as new content;
 * - **compare over a range of shifts**, so a hand that moved between shots is
 *   aligned rather than counted as difference.
 *
 * Measured on real captures: the same page shot twice scores 0.17, while five
 * pairs of genuinely different pages score 0.52 to 0.68.
 */
class PageSignature private constructor(
    private val values: FloatArray,
) {
    /**
     * Smallest mean absolute difference across the search window, 0 upward.
     * Lower means more alike; see [LIKELY_SAME_PAGE] for the decision point.
     */
    fun distanceTo(other: PageSignature): Float {
        var best = Float.MAX_VALUE
        for (shiftY in -SEARCH_RADIUS..SEARCH_RADIUS) {
            for (shiftX in -SEARCH_RADIUS..SEARCH_RADIUS) {
                var total = 0f
                var count = 0
                // Only the interior is compared: shifting exposes edges that
                // hold no matching content on the other side.
                for (y in SEARCH_RADIUS until HEIGHT - SEARCH_RADIUS) {
                    for (x in SEARCH_RADIUS until WIDTH - SEARCH_RADIUS) {
                        val otherY = y + shiftY
                        val otherX = x + shiftX
                        total += abs(values[y * WIDTH + x] - other.values[otherY * WIDTH + otherX])
                        count++
                    }
                }
                if (count > 0) best = min(best, total / count)
            }
        }
        return if (best == Float.MAX_VALUE) Float.MAX_VALUE else best
    }

    /** Whether [other] is, to the best of this measure, the same page. */
    fun looksLikeSamePageAs(other: PageSignature): Boolean = distanceTo(other) < LIKELY_SAME_PAGE

    companion object {
        private const val WIDTH = 64
        private const val HEIGHT = 48

        /** How far to search for alignment, in signature pixels. */
        private const val SEARCH_RADIUS = 6

        /**
         * Sits between the measured populations — duplicates at 0.17, distinct
         * pages from 0.52 — and nearer the duplicates, because wrongly keeping
         * a duplicate (delete it) is a smaller harm than wrongly skipping a
         * page the user actually wanted (photograph it again, unaware).
         */
        const val LIKELY_SAME_PAGE = 0.30f

        /** Builds a signature by box-averaging down and normalising. */
        fun of(image: GrayscaleImage): PageSignature {
            val values = FloatArray(WIDTH * HEIGHT)
            val blockWidth = max(1, image.width / WIDTH)
            val blockHeight = max(1, image.height / HEIGHT)

            for (y in 0 until HEIGHT) {
                for (x in 0 until WIDTH) {
                    var sum = 0L
                    var count = 0
                    val startX = x * image.width / WIDTH
                    val startY = y * image.height / HEIGHT
                    for (dy in 0 until blockHeight) {
                        for (dx in 0 until blockWidth) {
                            sum += image.luminanceAt(startX + dx, startY + dy)
                            count++
                        }
                    }
                    values[y * WIDTH + x] = sum.toFloat() / max(1, count)
                }
            }

            val mean = values.average().toFloat()
            var variance = 0.0
            values.forEach { variance += (it - mean).toDouble() * (it - mean) }
            // A flat image has no deviation to divide by; the epsilon keeps it
            // finite rather than producing NaNs that compare unpredictably.
            val deviation = max(1e-6f, sqrt(variance / values.size).toFloat())
            for (index in values.indices) values[index] = (values[index] - mean) / deviation

            return PageSignature(values)
        }
    }
}
