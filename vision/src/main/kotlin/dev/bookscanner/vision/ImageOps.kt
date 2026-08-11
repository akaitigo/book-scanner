package dev.bookscanner.vision

import dev.bookscanner.core.contracts.GrayscaleImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The image primitives the page detector is built from.
 *
 * Written here rather than pulled from a CV library because this project's CI
 * cannot execute one: OpenCV's Android binaries link against bionic and will
 * not load in a host JVM ([ADR-0008](../../../../../../docs/adr/0008-page-detection.md)).
 * Everything in this file is pure Kotlin, so every step is testable on
 * synthetic images with known answers.
 */
object ImageOps {
    /**
     * Downscales by an integer factor with box averaging.
     *
     * Detection runs on a small image on purpose: page edges are large
     * structures, and working at ~600 px instead of 4080 px is roughly fifty
     * times less work while making the result *less* sensitive to paper
     * texture and sensor noise. Averaging rather than sampling is what
     * suppresses that noise instead of aliasing it.
     */
    fun downscale(
        image: GrayscaleImage,
        factor: Int,
    ): GrayscaleImage {
        require(factor >= 1) { "Scale factor must be >= 1, got $factor" }
        if (factor == 1) return image

        val width = max(1, image.width / factor)
        val height = max(1, image.height / factor)
        val out = ByteArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (dy in 0 until factor) {
                    val sourceY = y * factor + dy
                    if (sourceY >= image.height) break
                    for (dx in 0 until factor) {
                        val sourceX = x * factor + dx
                        if (sourceX >= image.width) break
                        sum += image.luminanceAt(sourceX, sourceY)
                        count++
                    }
                }
                out[y * width + x] = (sum / max(1, count)).toByte()
            }
        }
        return GrayscaleImage(width, height, out)
    }

    /** Scale factor that brings the longest edge to at most [targetLongestEdge]. */
    fun scaleFactorFor(
        image: GrayscaleImage,
        targetLongestEdge: Int,
    ): Int {
        val longest = max(image.width, image.height)
        if (longest <= targetLongestEdge) return 1
        return max(1, longest / targetLongestEdge)
    }

    /**
     * Separable Gaussian blur.
     *
     * Separable because a 2-D Gaussian is the product of two 1-D ones: two
     * passes of width `k` instead of one of `k²`. At the radii edge detection
     * wants that is the difference between negligible and noticeable.
     */
    fun gaussianBlur(
        image: GrayscaleImage,
        radius: Int,
    ): GrayscaleImage {
        require(radius >= 0) { "Radius must be >= 0, got $radius" }
        if (radius == 0) return image

        val kernel = gaussianKernel(radius)
        val horizontal = ByteArray(image.width * image.height)
        val vertical = ByteArray(image.width * image.height)

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                var sum = 0f
                for (k in -radius..radius) {
                    sum += kernel[k + radius] * image.luminanceAt(x + k, y)
                }
                horizontal[y * image.width + x] = sum.roundToInt().coerceIn(0, 255).toByte()
            }
        }

        val pass = GrayscaleImage(image.width, image.height, horizontal)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                var sum = 0f
                for (k in -radius..radius) {
                    sum += kernel[k + radius] * pass.luminanceAt(x, y + k)
                }
                vertical[y * image.width + x] = sum.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return GrayscaleImage(image.width, image.height, vertical)
    }

    /** Normalized 1-D Gaussian with sigma tied to the radius (radius ≈ 3σ). */
    internal fun gaussianKernel(radius: Int): FloatArray {
        val sigma = max(0.5f, radius / 3f)
        val kernel = FloatArray(radius * 2 + 1)
        var total = 0f
        for (i in kernel.indices) {
            val offset = (i - radius).toFloat()
            kernel[i] = kotlin.math.exp(-(offset * offset) / (2f * sigma * sigma))
            total += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= total
        return kernel
    }

    /**
     * Sobel gradient magnitude and direction.
     *
     * Sobel rather than a plain difference because its 3x3 kernels smooth
     * along the edge while differentiating across it, which is what keeps a
     * page edge continuous over paper texture.
     */
    fun sobel(image: GrayscaleImage): GradientField {
        val magnitude = FloatArray(image.width * image.height)
        val direction = FloatArray(image.width * image.height)

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val tl = image.luminanceAt(x - 1, y - 1)
                val tc = image.luminanceAt(x, y - 1)
                val tr = image.luminanceAt(x + 1, y - 1)
                val ml = image.luminanceAt(x - 1, y)
                val mr = image.luminanceAt(x + 1, y)
                val bl = image.luminanceAt(x - 1, y + 1)
                val bc = image.luminanceAt(x, y + 1)
                val br = image.luminanceAt(x + 1, y + 1)

                val gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl)
                val gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr)
                val index = y * image.width + x
                magnitude[index] = sqrt((gx * gx + gy * gy).toFloat())
                direction[index] = kotlin.math.atan2(gy.toFloat(), gx.toFloat())
            }
        }
        return GradientField(image.width, image.height, magnitude, direction)
    }

    /**
     * Otsu's threshold over a gradient magnitude histogram.
     *
     * A fixed threshold cannot work across a range of lighting: the same page
     * photographed by a window and under a lamp produces gradients an order of
     * magnitude apart. Otsu picks the value that best separates the two modes
     * of whatever histogram it is actually given.
     *
     * **Zero-magnitude pixels are excluded.** After non-maximum suppression the
     * overwhelming majority of pixels are exactly zero — they are not edge
     * candidates, and including them makes the split degenerate: on a clean
     * image the zero bin dominates so completely that the optimal split lands
     * at zero, which then admits every pixel as an edge. That is not a
     * hypothetical; it made the detector fail on the *easiest* synthetic pages
     * while succeeding on noisy ones.
     */
    fun otsuThreshold(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val maxValue = values.max()
        if (maxValue <= 0f) return 0f

        val bins = 256
        val histogram = IntArray(bins)
        var counted = 0
        values.forEach { value ->
            if (value <= 0f) return@forEach
            val bin = ((value / maxValue) * (bins - 1)).roundToInt().coerceIn(0, bins - 1)
            histogram[bin]++
            counted++
        }
        if (counted == 0) return 0f

        val total = counted
        var sumAll = 0.0
        for (i in 0 until bins) sumAll += i.toDouble() * histogram[i]

        var sumBackground = 0.0
        var weightBackground = 0
        var best = 0.0
        var threshold = 0

        for (i in 0 until bins) {
            weightBackground += histogram[i]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break

            sumBackground += i.toDouble() * histogram[i]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sumAll - sumBackground) / weightForeground
            val between =
                weightBackground.toDouble() * weightForeground * (meanBackground - meanForeground) *
                    (meanBackground - meanForeground)
            if (between > best) {
                best = between
                threshold = i
            }
        }
        return (threshold.toFloat() / (bins - 1)) * maxValue
    }
}

/** Per-pixel gradient magnitude and direction produced by [ImageOps.sobel]. */
class GradientField(
    val width: Int,
    val height: Int,
    val magnitude: FloatArray,
    /** Gradient angle in radians, -π..π. */
    val direction: FloatArray,
) {
    fun magnitudeAt(
        x: Int,
        y: Int,
    ): Float {
        if (x !in 0 until width || y !in 0 until height) return 0f
        return magnitude[y * width + x]
    }

    fun directionAt(
        x: Int,
        y: Int,
    ): Float = direction[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    /**
     * Thins edges to one pixel by suppressing any gradient that is not a local
     * maximum along its own direction.
     *
     * Without it a page edge is a several-pixel-wide ridge, and every one of
     * those pixels votes in the Hough accumulator — smearing the peak that is
     * supposed to identify the edge.
     */
    fun nonMaximumSuppressed(): GradientField {
        val out = FloatArray(magnitude.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val value = magnitude[index]
                if (value <= 0f) continue

                // Quantise the gradient direction to one of four neighbour pairs.
                val angle = directionAt(x, y)
                val degrees = (Math.toDegrees(angle.toDouble()) + 180.0) % 180.0
                val (dx, dy) =
                    when {
                        degrees < 22.5 || degrees >= 157.5 -> 1 to 0
                        degrees < 67.5 -> 1 to 1
                        degrees < 112.5 -> 0 to 1
                        else -> -1 to 1
                    }
                if (value >= magnitudeAt(x - dx, y - dy) && value >= magnitudeAt(x + dx, y + dy)) {
                    out[index] = value
                }
            }
        }
        return GradientField(width, height, out, direction)
    }

    /** Coordinates of every pixel whose magnitude is at least [threshold]. */
    fun edgePoints(threshold: Float): List<EdgePoint> {
        val points = ArrayList<EdgePoint>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = magnitude[y * width + x]
                if (value >= threshold) points += EdgePoint(x, y, value, directionAt(x, y))
            }
        }
        return points
    }

    /**
     * Canny-style hysteresis: keep every pixel at or above [high], plus any
     * pixel at or above [low] that is connected to one of them.
     *
     * A single threshold has to be wrong somewhere. Set it high and a real edge
     * breaks wherever contrast dips; set it low and texture floods in. This is
     * not theoretical here — a slanted page edge rasterizes as a staircase
     * whose corners spike above a *straight* edge's response, so a single Otsu
     * threshold kept the slanted sides and discarded the horizontal ones
     * entirely, leaving no quadrilateral to build.
     */
    fun edgePointsWithHysteresis(
        high: Float,
        low: Float = high * DEFAULT_LOW_RATIO,
    ): List<EdgePoint> {
        val kept = BooleanArray(magnitude.size)
        val queue = ArrayDeque<Int>()

        for (index in magnitude.indices) {
            if (magnitude[index] >= high) {
                kept[index] = true
                queue += index
            }
        }

        // Grow along connected weak pixels: an edge is continuous, and this is
        // what recovers the parts of it that dipped below the strong threshold.
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % width
            val y = index / width
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val neighbour = ny * width + nx
                    if (kept[neighbour]) continue
                    if (magnitude[neighbour] >= low) {
                        kept[neighbour] = true
                        queue += neighbour
                    }
                }
            }
        }

        val points = ArrayList<EdgePoint>()
        for (index in kept.indices) {
            if (!kept[index]) continue
            val x = index % width
            val y = index / width
            points += EdgePoint(x, y, magnitude[index], directionAt(x, y))
        }
        return points
    }

    private companion object {
        /** Canny's usual low:high ratio. */
        const val DEFAULT_LOW_RATIO = 0.4f
    }
}

data class EdgePoint(
    val x: Int,
    val y: Int,
    val magnitude: Float,
    val direction: Float,
)

/** Absolute difference, kept here so call sites read as intent. */
internal fun angleDistance(
    a: Float,
    b: Float,
): Float {
    val diff = abs(a - b) % (2 * Math.PI.toFloat())
    return min(diff, 2 * Math.PI.toFloat() - diff)
}
