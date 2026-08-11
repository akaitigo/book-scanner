package dev.bookscanner.engine.production

import android.graphics.Bitmap
import dev.bookscanner.core.contracts.GrayscaleImage
import dev.bookscanner.core.contracts.PageDetection
import dev.bookscanner.core.contracts.PageDetector
import dev.bookscanner.core.contracts.PageGeometry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs a [PageDetector] against a stored page file.
 *
 * The detector itself is pure JVM and knows nothing about Android — that is
 * what lets it be tested and benchmarked off-device (ADR-0008). This is the
 * only place that bridges the two, and it is deliberately thin: decode,
 * convert to luminance, delegate.
 */
class AndroidPageDetection(
    private val detector: PageDetector,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * The detector downscales again internally, but decoding straight to a
     * small bitmap avoids materializing a 12 MP one just to shrink it.
     */
    private val decodeLongestEdge: Int = DEFAULT_DECODE_EDGE,
) {
    /**
     * @param geometry applied before detection, so the boundary comes back in
     *   the coordinate system the user is looking at — the same space
     *   [PageGeometry.boundary] is defined in.
     */
    suspend fun detect(
        file: File,
        geometry: PageGeometry = PageGeometry.IDENTITY,
    ): PageDetection =
        withContext(ioDispatcher) {
            // Detection runs on the rotated page but never on a cropped or
            // already-corrected one: it is looking for the page in the photo,
            // not inside a previous correction.
            val orientationOnly = PageGeometry(rotationDegrees = geometry.rotationDegrees)
            val bitmap = PageImageDecoder.decode(file, orientationOnly, decodeLongestEdge)
            try {
                detector.detect(bitmap.toGrayscale())
            } finally {
                bitmap.recycle()
            }
        }

    private companion object {
        const val DEFAULT_DECODE_EDGE = 1200
    }
}

/**
 * Converts to 8-bit luminance with the Rec. 601 weights.
 *
 * Not a plain channel average: the eye is far more sensitive to green than to
 * blue, and averaging would make a blue page edge nearly vanish while
 * exaggerating a green one.
 *
 * Public because the capture screen fingerprints frames for duplicate
 * detection, and that has to see the same luminance the detector does.
 */
fun Bitmap.toGrayscale(): GrayscaleImage {
    val argb = IntArray(width * height)
    getPixels(argb, 0, width, 0, 0, width, height)

    val luminance = ByteArray(argb.size)
    for (index in argb.indices) {
        val pixel = argb[index]
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        luminance[index] = ((red * 299 + green * 587 + blue * 114) / 1000).coerceIn(0, 255).toByte()
    }
    return GrayscaleImage(width, height, luminance)
}
