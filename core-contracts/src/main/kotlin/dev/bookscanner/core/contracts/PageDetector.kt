package dev.bookscanner.core.contracts

/**
 * A point in normalized image coordinates: (0,0) is the top-left of the image
 * and (1,1) the bottom-right, so a boundary survives resizing and is
 * comparable across engines regardless of the resolution each one worked at.
 */
data class NormalizedPoint(
    val x: Float,
    val y: Float,
)

/**
 * The four corners of a detected page, in clockwise order starting from the
 * top-left as the page appears in the image.
 *
 * Deliberately a quadrilateral rather than a rectangle: a photographed page is
 * a perspective projection, and forcing it to an axis-aligned rect before
 * correction would throw away the very information the correction needs.
 */
data class PageBoundary(
    val topLeft: NormalizedPoint,
    val topRight: NormalizedPoint,
    val bottomRight: NormalizedPoint,
    val bottomLeft: NormalizedPoint,
) {
    val corners: List<NormalizedPoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Fraction of the image area the quadrilateral covers, by the shoelace formula. */
    val areaFraction: Float
        get() {
            val points = corners
            var sum = 0f
            for (index in points.indices) {
                val current = points[index]
                val next = points[(index + 1) % points.size]
                sum += current.x * next.y - next.x * current.y
            }
            return kotlin.math.abs(sum) / 2f
        }

    companion object {
        /** The whole image — what "no crop needed" means. */
        val FULL =
            PageBoundary(
                topLeft = NormalizedPoint(0f, 0f),
                topRight = NormalizedPoint(1f, 0f),
                bottomRight = NormalizedPoint(1f, 1f),
                bottomLeft = NormalizedPoint(0f, 1f),
            )
    }
}

/**
 * A detection attempt.
 *
 * Detection failing is normal, not exceptional: a page on a busy background in
 * poor light may genuinely not be findable. The product requirement is that a
 * failure leaves the user with a usable manual path, so this reports absence
 * rather than throwing.
 */
data class PageDetection(
    val boundary: PageBoundary?,
    /**
     * How much the detector trusts this boundary, 0..1. Used to decide whether
     * to apply it automatically or merely propose it, and recorded by the
     * benchmark harness.
     */
    val confidence: Float,
) {
    val found: Boolean get() = boundary != null

    companion object {
        val NOT_FOUND = PageDetection(boundary = null, confidence = 0f)
    }
}

/**
 * Finds the page boundary in a captured image.
 *
 * Implementations take a [GrayscaleImage] rather than a platform bitmap so a
 * detector can be written, tested and benchmarked without Android — which is
 * what makes the Production and From-Scratch engines comparable on identical
 * inputs (AGENTS.md §7, §10).
 */
interface PageDetector {
    val engine: EngineId

    fun detect(image: GrayscaleImage): PageDetection
}

/**
 * An 8-bit grayscale image: the pipeline's platform-neutral pixel format.
 *
 * Detection needs luminance, not colour, so converting once at the boundary
 * keeps every detector free of decoding concerns and a quarter of the memory.
 */
class GrayscaleImage(
    val width: Int,
    val height: Int,
    /** Row-major luminance, one byte per pixel, `y * width + x`. */
    val pixels: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "Image must have positive dimensions, got ${width}x$height" }
        require(pixels.size == width * height) {
            "Expected ${width * height} pixels for ${width}x$height, got ${pixels.size}"
        }
    }

    /** Luminance at (x, y) as 0..255. Coordinates are clamped to the edges. */
    fun luminanceAt(
        x: Int,
        y: Int,
    ): Int {
        val clampedX = x.coerceIn(0, width - 1)
        val clampedY = y.coerceIn(0, height - 1)
        return pixels[clampedY * width + clampedX].toInt() and 0xFF
    }
}
