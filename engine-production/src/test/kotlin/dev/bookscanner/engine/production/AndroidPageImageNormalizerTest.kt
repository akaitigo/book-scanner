package dev.bookscanner.engine.production

import androidx.exifinterface.media.ExifInterface
import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.pdf.parseJpegMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The normalizer establishes the storage invariant the exporter's passthrough
 * path depends on: every committed page file is a baseline JPEG, and any
 * declared orientation lives in the page's geometry rather than in its pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidPageImageNormalizerTest {
    private lateinit var dir: File
    private val normalizer = AndroidPageImageNormalizer(ioDispatcher = Dispatchers.Unconfined)

    @Before
    fun setUp() {
        dir =
            File.createTempFile("normalizer-test", "").let {
                it.delete()
                it.mkdirs()
                it
            }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private suspend fun normalize(input: ByteArray): Pair<ByteArray, dev.bookscanner.core.contracts.NormalizedPage> {
        val out = ByteArrayOutputStream()
        val result = normalizer.normalize(ByteArrayInputStream(input), out)
        return out.toByteArray() to result
    }

    @Test
    fun `baseline jpeg is copied byte for byte`() =
        runTest {
            val source =
                TestImages
                    .writeJpeg(
                        TestImages.quadrantBitmap(320, 240),
                        File(dir, "camera.jpg"),
                    ).readBytes()

            val (output, result) = normalize(source)

            assertTrue(result.losslessCopy, "a camera JPEG must not be re-encoded")
            assertContentEquals(source, output)
            assertEquals(PageGeometry.IDENTITY, result.geometry)
        }

    @Test
    fun `png is transcoded to an embeddable baseline jpeg`() =
        runTest {
            val source =
                TestImages
                    .writePng(
                        TestImages.quadrantBitmap(120, 90),
                        File(dir, "picked.png"),
                    ).readBytes()

            val (output, result) = normalize(source)

            assertFalse(result.losslessCopy)
            val metadata = requireNotNull(parseJpegMetadata(output))
            assertTrue(metadata.isEmbeddable, "normalized output must be embeddable")
            assertEquals(120, metadata.widthPx)
            assertEquals(90, metadata.heightPx)
        }

    @Test
    fun `exif orientation becomes geometry instead of rotated pixels`() =
        runTest {
            val file = TestImages.writeJpeg(TestImages.quadrantBitmap(320, 240), File(dir, "rotated.jpg"))
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val source = file.readBytes()

            val (output, result) = normalize(source)

            assertTrue(result.losslessCopy, "orientation alone must not force a re-encode")
            assertContentEquals(source, output)
            assertEquals(90, result.geometry.rotationDegrees)
        }

    @Test
    fun `exif orientation normal yields identity geometry`() =
        runTest {
            val file = TestImages.writeJpeg(TestImages.quadrantBitmap(64, 64), File(dir, "normal.jpg"))
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }

            val (_, result) = normalize(file.readBytes())

            assertEquals(PageGeometry.IDENTITY, result.geometry)
        }

    @Test
    fun `empty input is rejected`() =
        runTest {
            assertFailsWith<java.io.IOException> { normalize(ByteArray(0)) }
        }

    @Test
    fun `corrupt input is rejected rather than stored`() =
        runTest {
            assertFailsWith<java.io.IOException> { normalize("this is not an image".toByteArray()) }
        }
}
