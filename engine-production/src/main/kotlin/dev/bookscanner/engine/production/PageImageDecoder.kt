package dev.bookscanner.engine.production

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import dev.bookscanner.core.contracts.PageGeometry
import java.io.File
import java.io.IOException
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes a page image with its [PageGeometry] applied, in a single pass.
 *
 * Both the exporter and the transformer go through here rather than chaining
 * (transform → JPEG → decode → draw): that composition would cost two lossy
 * re-encodes per page and inflate both export time and output size.
 *
 * EXIF orientation is deliberately NOT read here. Page files are normalized
 * on the way in (see PageImageNormalizer), which moves any declared
 * orientation into [PageGeometry.rotationDegrees]. Applying it again would
 * rotate the page twice.
 *
 * Order of operations — rotation, then crop — matches the [PageGeometry]
 * contract: crop coordinates are expressed in the orientation the user was
 * looking at.
 */
internal object PageImageDecoder {
    /**
     * @param maxDimension if set, the *returned* bitmap's longest edge is at
     *   most this many pixels. Downsampling happens during decode where
     *   possible, so a preview never materializes a full-resolution bitmap.
     */
    fun decode(
        file: File,
        geometry: PageGeometry,
        maxDimension: Int? = null,
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Not a decodable image: $file")
        }

        val cropScale = geometry.crop?.let { max(it.width, it.height) } ?: 1f

        val options =
            BitmapFactory.Options().apply {
                inSampleSize =
                    sampleSizeFor(
                        sourceLongestEdge = max(bounds.outWidth, bounds.outHeight),
                        // The crop shrinks the result, so the source may be sampled
                        // more aggressively than maxDimension alone would suggest.
                        targetLongestEdge = maxDimension?.let { (it / cropScale).roundToInt() },
                    )
            }
        val decoded =
            BitmapFactory.decodeFile(file.absolutePath, options)
                ?: throw IOException("Failed to decode image: $file")

        val rotated = decoded.rotatedBy(geometry.rotationDegrees)
        // Perspective first, then crop: the boundary is expressed against the
        // photographed page, and cropping before straightening would mean
        // cropping coordinates that no longer exist.
        val straightened = rotated.perspectiveCorrected(geometry)
        val cropped = straightened.cropped(geometry)
        return cropped.downscaledTo(maxDimension)
    }

    /**
     * Largest power-of-two sample size that keeps the decoded image at or
     * above [targetLongestEdge]; decoding below the target and scaling up
     * would lose detail the source actually had.
     */
    private fun sampleSizeFor(
        sourceLongestEdge: Int,
        targetLongestEdge: Int?,
    ): Int {
        if (targetLongestEdge == null || targetLongestEdge <= 0) return 1
        var sample = 1
        while (sourceLongestEdge / (sample * 2) >= targetLongestEdge) {
            sample *= 2
        }
        return sample
    }

    private fun Bitmap.rotatedBy(degrees: Int): Bitmap {
        val normalized = Math.floorMod(degrees, 360)
        if (normalized == 0) return this
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (rotated !== this) recycle()
        return rotated
    }

    /**
     * Warps the page quadrilateral onto a rectangle with the platform's
     * `Matrix.setPolyToPoly`.
     *
     * No CV library is involved: `setPolyToPoly` with four point pairs *is* a
     * perspective transform, it operates on the `Bitmap` directly (so there is
     * no conversion to and from a library's own image type), and it costs
     * nothing in APK size — see ADR-0008.
     *
     * The output size is taken from the longest opposing edges, so a page
     * photographed at an angle is straightened without being squashed to the
     * dimensions of its foreshortened side.
     */
    private fun Bitmap.perspectiveCorrected(geometry: PageGeometry): Bitmap {
        val boundary = geometry.boundary ?: return this

        val source =
            floatArrayOf(
                boundary.topLeft.x * width,
                boundary.topLeft.y * height,
                boundary.topRight.x * width,
                boundary.topRight.y * height,
                boundary.bottomRight.x * width,
                boundary.bottomRight.y * height,
                boundary.bottomLeft.x * width,
                boundary.bottomLeft.y * height,
            )

        val topEdge = hypot(source[2] - source[0], source[3] - source[1])
        val bottomEdge = hypot(source[4] - source[6], source[5] - source[7])
        val leftEdge = hypot(source[0] - source[6], source[1] - source[7])
        val rightEdge = hypot(source[2] - source[4], source[3] - source[5])

        val outWidth = max(1, max(topEdge, bottomEdge).roundToInt())
        val outHeight = max(1, max(leftEdge, rightEdge).roundToInt())

        val destination =
            floatArrayOf(
                0f,
                0f,
                outWidth.toFloat(),
                0f,
                outWidth.toFloat(),
                outHeight.toFloat(),
                0f,
                outHeight.toFloat(),
            )

        val matrix = Matrix()
        // Returns false for degenerate quadrilaterals; keeping the original is
        // better than producing a collapsed page.
        if (!matrix.setPolyToPoly(source, 0, destination, 0, 4)) return this

        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(this, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        recycle()
        return output
    }

    private fun Bitmap.cropped(geometry: PageGeometry): Bitmap {
        val crop = geometry.crop ?: return this
        val left = (width * crop.left).roundToInt().coerceIn(0, width - 1)
        val top = (height * crop.top).roundToInt().coerceIn(0, height - 1)
        val cropWidth = max(1, (width * crop.width).roundToInt()).coerceAtMost(width - left)
        val cropHeight = max(1, (height * crop.height).roundToInt()).coerceAtMost(height - top)
        if (left == 0 && top == 0 && cropWidth == width && cropHeight == height) return this
        val cropped = Bitmap.createBitmap(this, left, top, cropWidth, cropHeight)
        if (cropped !== this) recycle()
        return cropped
    }

    /**
     * Final exact resize. [sampleSizeFor] only gets within a power of two of
     * the target, so a preview asking for 512 px could otherwise come back at
     * 1024 px and waste memory in the UI's bitmap cache.
     */
    private fun Bitmap.downscaledTo(maxDimension: Int?): Bitmap {
        if (maxDimension == null || maxDimension <= 0) return this
        val longest = max(width, height)
        if (longest <= maxDimension) return this
        val scale = maxDimension.toFloat() / longest
        val scaled =
            Bitmap.createScaledBitmap(
                this,
                max(1, (width * scale).roundToInt()),
                max(1, (height * scale).roundToInt()),
                true,
            )
        if (scaled !== this) recycle()
        return scaled
    }
}
