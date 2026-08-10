package dev.bookscanner.engine.production

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import dev.bookscanner.core.contracts.EngineId
import dev.bookscanner.core.contracts.NormalizedPage
import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.core.contracts.PageImageNormalizer
import dev.bookscanner.pdf.parseJpegMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Production [PageImageNormalizer].
 *
 * Fast path — a baseline JPEG is copied byte for byte, and any EXIF
 * orientation it declares becomes page geometry instead of being burned into
 * pixels. Camera captures take this path, so a scan is never re-encoded on the
 * way in.
 *
 * Slow path — anything else (PNG, WebP, HEIC, progressive JPEG, CMYK) is
 * decoded once and re-encoded as a baseline JPEG, because the storage
 * invariant and `/DCTDecode` both require it.
 */
class AndroidPageImageNormalizer(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val jpegQuality: Int = DEFAULT_QUALITY,
) : PageImageNormalizer {
    override val engine: EngineId = EngineId.PRODUCTION

    override suspend fun normalize(
        input: InputStream,
        output: OutputStream,
    ): NormalizedPage =
        withContext(ioDispatcher) {
            val bytes = input.readBytes()
            if (bytes.isEmpty()) throw IOException("Empty image input")

            val metadata = parseJpegMetadata(bytes)
            if (metadata?.isEmbeddable == true) {
                output.write(bytes)
                output.flush()
                return@withContext NormalizedPage(
                    geometry = PageGeometry(rotationDegrees = readExifRotation(bytes)),
                    losslessCopy = true,
                )
            }

            // Re-encode path. EXIF orientation is applied to the pixels here,
            // since the re-encoded output carries no EXIF of its own.
            val decoded =
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("Unsupported or corrupt image (${bytes.size} bytes)")
            val upright = decoded.rotatedBy(readExifRotation(bytes))
            try {
                if (!upright.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)) {
                    throw IOException("Failed to re-encode image as JPEG")
                }
                output.flush()
            } finally {
                upright.recycle()
            }
            NormalizedPage(geometry = PageGeometry.IDENTITY, losslessCopy = false)
        }

    private fun readExifRotation(bytes: ByteArray): Int =
        runCatching {
            when (
                ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)

    private fun Bitmap.rotatedBy(degrees: Int): Bitmap {
        val normalized = Math.floorMod(degrees, 360)
        if (normalized == 0) return this
        val matrix = android.graphics.Matrix().apply { postRotate(normalized.toFloat()) }
        val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (rotated !== this) recycle()
        return rotated
    }

    private companion object {
        const val DEFAULT_QUALITY = 92
    }
}
