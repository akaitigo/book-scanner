package dev.bookscanner.engine.production

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File

/**
 * Synthetic page images for tests. Quadrant-coloured bitmaps make rotation
 * and crop verifiable by sampling a few pixels, and photo-like noise makes
 * JPEG size measurements representative of real scans instead of flat colour
 * that compresses to nothing.
 */
internal object TestImages {
    val TOP_LEFT = Color.RED
    val TOP_RIGHT = Color.GREEN
    val BOTTOM_LEFT = Color.BLUE
    val BOTTOM_RIGHT = Color.YELLOW

    fun quadrantBitmap(
        width: Int = 400,
        height: Int = 200,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        paint.color = TOP_LEFT
        canvas.drawRect(0f, 0f, halfWidth, halfHeight, paint)
        paint.color = TOP_RIGHT
        canvas.drawRect(halfWidth, 0f, width.toFloat(), halfHeight, paint)
        paint.color = BOTTOM_LEFT
        canvas.drawRect(0f, halfHeight, halfWidth, height.toFloat(), paint)
        paint.color = BOTTOM_RIGHT
        canvas.drawRect(halfWidth, halfHeight, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    /**
     * Deterministic pseudo-photographic noise. A flat bitmap would make the
     * PDF size gate meaningless — real page scans carry paper texture and
     * text edges that do not compress away.
     */
    fun noisyBitmap(
        width: Int,
        height: Int,
        seed: Int = 42,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        var state = seed.toLong() or 1L
        for (index in pixels.indices) {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            val value = ((state ushr 33).toInt() and 0xFF)
            // Bias toward paper-white with darker speckles, like a scanned page.
            val level = (200 + (value % 56)).coerceAtMost(255)
            val ink = if (value % 17 == 0) value % 90 else level
            pixels[index] = Color.rgb(ink, ink, ink)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun writeJpeg(
        bitmap: Bitmap,
        file: File,
        quality: Int = 90,
    ): File {
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                "Failed to encode test JPEG to $file"
            }
        }
        return file
    }

    fun writePng(
        bitmap: Bitmap,
        file: File,
    ): File {
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "Failed to encode test PNG to $file"
            }
        }
        return file
    }
}
