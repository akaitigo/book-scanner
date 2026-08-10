package dev.bookscanner.engine.production

import dev.bookscanner.core.contracts.CropRect
import dev.bookscanner.core.contracts.ExportPage
import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.pdf.parseJpegMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Structural assertions use [MinimalPdfReader] rather than a PDF library,
 * because Robolectric's classpath is already heavy; the full independent
 * verification of the writer's output lives in `:pdf-writer`, where PDFBox
 * parses and renders it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JpegPdfExporterTest {
    private lateinit var dir: File
    private val transformer = BitmapPageTransformer(ioDispatcher = Dispatchers.Unconfined)

    private fun exporter() =
        JpegPdfExporter(
            transformer = transformer,
            ioDispatcher = Dispatchers.Unconfined,
        )

    @Before
    fun setUp() {
        dir =
            File.createTempFile("exporter-test", "").let {
                it.delete()
                it.mkdirs()
                it
            }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun jpegPage(
        name: String,
        width: Int = 400,
        height: Int = 200,
    ): File = TestImages.writeJpeg(TestImages.quadrantBitmap(width, height), File(dir, name))

    @Test
    fun `exports a valid multi page pdf in order`() =
        runTest {
            val pages = listOf("a.jpg", "b.jpg", "c.jpg").map { ExportPage(jpegPage(it)) }
            val out = ByteArrayOutputStream()

            exporter().export(pages, out)

            val reader = MinimalPdfReader(out.toByteArray())
            assertTrue(reader.isPdf)
            assertEquals(3, reader.pageCount)
            assertEquals(3, reader.imageCount)
            assertTrue(reader.hasTrailer, "PDF must end with startxref/%%EOF")
            assertTrue(reader.hasXref)
        }

    @Test
    fun `uncropped pages are embedded without re-encoding`() =
        runTest {
            val file = jpegPage("page.jpg")
            val original = file.readBytes()
            val out = ByteArrayOutputStream()

            val exporter = exporter()
            exporter.export(listOf(ExportPage(file)), out)

            assertEquals(1, exporter.lastExportStats.passthroughPages)
            assertEquals(0, exporter.lastExportStats.reencodedPages)
            assertTrue(
                out.toByteArray().containsSubsequence(original),
                "the original JPEG bytes should appear verbatim in the PDF",
            )
            assertEquals(setOf("DCTDecode"), MinimalPdfReader(out.toByteArray()).imageFilters)
        }

    @Test
    fun `rotation alone still uses the passthrough path`() =
        runTest {
            val file = jpegPage("page.jpg")
            val out = ByteArrayOutputStream()

            val exporter = exporter()
            exporter.export(listOf(ExportPage(file, PageGeometry(rotationDegrees = 90))), out)

            assertEquals(1, exporter.lastExportStats.passthroughPages)
            assertTrue(
                String(out.toByteArray(), Charsets.ISO_8859_1).contains("/Rotate 90"),
                "rotation should be a page attribute, not re-encoded pixels",
            )
        }

    @Test
    fun `cropped pages are re-encoded and must not also carry a page rotation`() =
        runTest {
            val file = jpegPage("page.jpg")
            val geometry =
                PageGeometry(
                    rotationDegrees = 90,
                    crop = CropRect(0f, 0f, 0.5f, 0.5f),
                )
            val out = ByteArrayOutputStream()

            val exporter = exporter()
            exporter.export(listOf(ExportPage(file, geometry)), out)

            assertEquals(0, exporter.lastExportStats.passthroughPages)
            assertEquals(1, exporter.lastExportStats.reencodedPages)
            // Rotation is baked into the pixels here; a non-zero /Rotate would
            // rotate the page a second time in the reader.
            assertTrue(
                String(out.toByteArray(), Charsets.ISO_8859_1).contains("/Rotate 0"),
                "re-encoded pages must have /Rotate 0",
            )
        }

    @Test
    fun `non baseline input is re-encoded rather than rejected`() =
        runTest {
            // A PNG on disk violates the storage invariant, but an export must not
            // fail at the very end of a long scanning session because of it.
            val png = TestImages.writePng(TestImages.quadrantBitmap(120, 80), File(dir, "page.png"))
            val out = ByteArrayOutputStream()

            val exporter = exporter()
            exporter.export(listOf(ExportPage(png)), out)

            assertEquals(0, exporter.lastExportStats.passthroughPages)
            assertEquals(1, exporter.lastExportStats.reencodedPages)
            assertEquals(1, MinimalPdfReader(out.toByteArray()).pageCount)
        }

    @Test
    fun `mixed session reports both paths`() =
        runTest {
            val plain = ExportPage(jpegPage("a.jpg"))
            val cropped = ExportPage(jpegPage("b.jpg"), PageGeometry(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f)))
            val out = ByteArrayOutputStream()

            val exporter = exporter()
            exporter.export(listOf(plain, cropped, plain), out)

            assertEquals(2, exporter.lastExportStats.passthroughPages)
            assertEquals(1, exporter.lastExportStats.reencodedPages)
            assertEquals(3, MinimalPdfReader(out.toByteArray()).pageCount)
        }

    @Test
    fun `progress is reported for every page`() =
        runTest {
            val pages = (1..4).map { ExportPage(jpegPage("p$it.jpg")) }
            val progress = mutableListOf<Pair<Int, Int>>()

            exporter().export(pages, ByteArrayOutputStream()) { done, total -> progress += done to total }

            assertContentEquals(listOf(1 to 4, 2 to 4, 3 to 4, 4 to 4), progress)
        }

    @Test
    fun `empty page list is rejected`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                exporter().export(emptyList(), ByteArrayOutputStream())
            }
        }

    @Test
    fun `cancellation stops the export`() =
        runTest {
            val pages = (1..5).map { ExportPage(jpegPage("p$it.jpg")) }
            val out = ByteArrayOutputStream()

            assertFailsWith<CancellationException> {
                exporter().export(pages, out) { done, _ ->
                    if (done == 2) throw CancellationException("user cancelled")
                }
            }
            // A cancelled export must never leave a document that looks complete.
            assertTrue(!MinimalPdfReader(out.toByteArray()).hasTrailer)
        }

    @Test
    fun `output size stays close to the sum of source jpegs`() =
        runTest {
            val files =
                (1..8).map { index ->
                    TestImages.writeJpeg(
                        TestImages.noisyBitmap(600, 800, seed = index),
                        File(dir, "noisy$index.jpg"),
                    )
                }
            val out = ByteArrayOutputStream()

            exporter().export(files.map { ExportPage(it) }, out)

            val sourceTotal = files.sumOf { it.length() }
            val ratio = out.size().toDouble() / sourceTotal
            println("MEASURE exporter-size-ratio pages=${files.size} source=${sourceTotal}B pdf=${out.size()}B ratio=$ratio")
            assertTrue(ratio <= 1.05, "PDF/source ratio was $ratio, budget 1.05")
        }

    @Test
    fun `re-encoded pages remain baseline jpeg so they stay embeddable`() =
        runTest {
            val out = ByteArrayOutputStream()
            transformer.transform(
                input = jpegPage("page.jpg"),
                geometry = PageGeometry(rotationDegrees = 90, crop = CropRect(0f, 0f, 0.5f, 1f)),
                output = out,
            )

            val metadata = requireNotNull(parseJpegMetadata(out.toByteArray()))
            assertTrue(metadata.isEmbeddable, "transformer output must be embeddable in a PDF")
        }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
