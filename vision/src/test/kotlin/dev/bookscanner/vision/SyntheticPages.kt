package dev.bookscanner.vision

import dev.bookscanner.core.contracts.GrayscaleImage
import dev.bookscanner.core.contracts.NormalizedPoint
import dev.bookscanner.core.contracts.PageBoundary
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Synthetic page photographs with **known** corners.
 *
 * Ground truth is the point: detection quality can only be a number if the
 * right answer is known, and it cannot be known for a real photograph without
 * hand-labelling. These stand in for the benchmark categories in
 * `docs/benchmark.md` that can be generated — perspective, low contrast,
 * noise, a busy background — while real-paper categories stay device work.
 */
internal object SyntheticPages {
    /**
     * A light quadrilateral page on a darker background.
     *
     * @param corners page corners in normalized coordinates, clockwise from
     *   top-left.
     * @param noise standard deviation of per-pixel noise, in luminance levels.
     * @param backgroundClutter draws darker rectangles behind the page, which
     *   is what a desk with objects on it looks like to an edge detector.
     */
    fun page(
        width: Int = 480,
        height: Int = 640,
        corners: PageBoundary = defaultCorners,
        pageLuminance: Int = 235,
        backgroundLuminance: Int = 60,
        noise: Int = 0,
        backgroundClutter: Boolean = false,
        seed: Long = 42,
    ): GrayscaleImage {
        val pixels = ByteArray(width * height) { backgroundLuminance.toByte() }
        var state = seed or 1L

        fun nextNoise(): Int {
            if (noise == 0) return 0
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33).toInt() % (noise * 2 + 1)) - noise
        }

        if (backgroundClutter) {
            // Two rectangles with strong straight edges: the detector must not
            // mistake furniture for the page.
            fillRect(pixels, width, height, 0, 0, width / 3, height / 5, backgroundLuminance + 90)
            fillRect(
                pixels,
                width,
                height,
                (width * 0.6f).toInt(),
                (height * 0.8f).toInt(),
                width,
                height,
                backgroundLuminance + 60,
            )
        }

        val points =
            corners.corners.map { point ->
                point.x * (width - 1) to point.y * (height - 1)
            }

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (pointInPolygon(x.toFloat(), y.toFloat(), points)) {
                    val value = (pageLuminance + nextNoise()).coerceIn(0, 255)
                    pixels[y * width + x] = value.toByte()
                } else if (noise > 0) {
                    val current = pixels[y * width + x].toInt() and 0xFF
                    pixels[y * width + x] = (current + nextNoise()).coerceIn(0, 255).toByte()
                }
            }
        }
        return GrayscaleImage(width, height, pixels)
    }

    /** An image with no page at all — a detector must not invent one. */
    fun blank(
        width: Int = 480,
        height: Int = 640,
        luminance: Int = 128,
    ): GrayscaleImage = GrayscaleImage(width, height, ByteArray(width * height) { luminance.toByte() })

    val defaultCorners =
        PageBoundary(
            topLeft = NormalizedPoint(0.12f, 0.10f),
            topRight = NormalizedPoint(0.88f, 0.10f),
            bottomRight = NormalizedPoint(0.88f, 0.90f),
            bottomLeft = NormalizedPoint(0.12f, 0.90f),
        )

    /** A page seen from an angle: the top edge is narrower than the bottom. */
    val perspectiveCorners =
        PageBoundary(
            topLeft = NormalizedPoint(0.22f, 0.12f),
            topRight = NormalizedPoint(0.78f, 0.12f),
            bottomRight = NormalizedPoint(0.90f, 0.88f),
            bottomLeft = NormalizedPoint(0.10f, 0.88f),
        )

    private fun fillRect(
        pixels: ByteArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        luminance: Int,
    ) {
        for (y in max(0, top) until min(height, bottom)) {
            for (x in max(0, left) until min(width, right)) {
                pixels[y * width + x] = luminance.coerceIn(0, 255).toByte()
            }
        }
    }

    private fun pointInPolygon(
        x: Float,
        y: Float,
        polygon: List<Pair<Float, Float>>,
    ): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val (xi, yi) = polygon[i]
            val (xj, yj) = polygon[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}

/**
 * Mean distance between corresponding corners, in normalized units — the
 * "corner positional error" the benchmark plan asks for.
 */
internal fun cornerError(
    expected: PageBoundary,
    actual: PageBoundary,
): Float {
    val pairs = expected.corners.zip(actual.corners)
    return pairs
        .map { (e, a) -> kotlin.math.hypot(e.x - a.x, e.y - a.y) }
        .average()
        .toFloat()
}

/** Formats an error for a test message, so failures carry the number. */
internal fun Float.asError(): String = "${(this * 1000).roundToInt() / 1000f}"
