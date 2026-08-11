package dev.bookscanner.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.bookscanner.core.contracts.CropRect
import dev.bookscanner.core.contracts.ExportPage
import dev.bookscanner.core.contracts.PageGeometry
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
import kotlin.test.assertTrue

/**
 * Milestone 1's book-scale acceptance test: a 120-page session goes through
 * the real pipeline — normalize, ingest, reorder, edit, export — with the real
 * repository, normalizer, transformer and PDF writer. No fakes.
 *
 * The point is the properties that only break at scale: page order surviving a
 * restart, memory staying flat because pages stream, and the exported PDF
 * being the size of its inputs rather than a multiple of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookScaleSmokeTest {
    private lateinit var root: File
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        root =
            File.createTempFile("smoke", "").let {
                it.delete()
                it.mkdirs()
                it
            }
        container = AppContainer(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** A page image with a per-page marker colour, so order is verifiable. */
    private fun pageJpeg(index: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawRect(
                0f,
                0f,
                PAGE_WIDTH / 2f,
                PAGE_HEIGHT / 2f,
                Paint().apply { color = Color.rgb((index * 2) % 256, (index * 5) % 256, (index * 11) % 256) },
            )
        }
        return ByteArrayOutputStream()
            .also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            .toByteArray()
            .also { bitmap.recycle() }
    }

    @Test
    fun `a 120 page book survives capture, restart, reorder, edit and export`() =
        runTest {
            val session = container.repository.createSession("Book scale smoke")

            repeat(PAGE_COUNT) { index ->
                container.ingestor.ingest(session.id, pageJpeg(index))
            }

            // 1. Every page is present and in capture order after a fresh
            //    repository reads the session back off disk.
            val reopened =
                requireNotNull(
                    AppContainer(root).repository.getSession(session.id),
                ) { "session should survive a process restart" }
            assertEquals(PAGE_COUNT, reopened.pageCount)
            assertTrue(!reopened.recovered, "a clean session must not be flagged as recovered")

            // 2. Reordering a book-scale session persists.
            val moved = reopened.pages.last()
            val newOrder = listOf(moved.id) + reopened.pages.dropLast(1).map { it.id }
            container.repository.reorderPages(session.id, newOrder)
            val afterReorder = requireNotNull(AppContainer(root).repository.getSession(session.id))
            assertContentEquals(newOrder, afterReorder.pages.map { it.id })

            // 3. A geometry edit touches one page and nothing else.
            val edited = afterReorder.pages[5]
            container.repository.updatePageGeometry(
                session.id,
                edited.id,
                PageGeometry(rotationDegrees = 90, crop = CropRect(0.05f, 0.05f, 0.95f, 0.95f)),
            )
            val afterEdit = requireNotNull(AppContainer(root).repository.getSession(session.id))
            assertEquals(90, requireNotNull(afterEdit.page(edited.id)).geometry.rotationDegrees)
            assertEquals(
                PageGeometry.IDENTITY,
                requireNotNull(afterEdit.page(afterEdit.pages[6].id)).geometry,
            )

            // 4. Export the whole book.
            val exportPages =
                afterEdit.pages.map { page ->
                    ExportPage(container.repository.pageFile(session.id, page), page.geometry)
                }
            val output = ByteArrayOutputStream()
            var lastProgress = 0
            container.exporter.export(exportPages, output) { done, total ->
                assertEquals(PAGE_COUNT, total)
                assertEquals(lastProgress + 1, done, "progress must advance one page at a time")
                lastProgress = done
            }
            assertEquals(PAGE_COUNT, lastProgress)

            val pdf = output.toByteArray()
            val sourceBytes = exportPages.sumOf { it.imageFile.length() }
            val ratio = pdf.size.toDouble() / sourceBytes
            println(
                "MEASURE book-scale pages=$PAGE_COUNT source=${sourceBytes}B pdf=${pdf.size}B ratio=$ratio",
            )

            // Asserted as bytes-per-page, not as a ratio. PDF overhead is a
            // fixed cost per page (object dictionaries plus an xref row), so a
            // ratio only measures how large the test's images happen to be —
            // these synthetic near-blank pages are ~2.7 KB, where real scans
            // are hundreds of KB. The invariant that actually holds is the
            // constant.
            val overheadPerPage = (pdf.size - sourceBytes).toDouble() / PAGE_COUNT
            println("MEASURE book-scale-overhead bytesPerPage=$overheadPerPage")
            assertTrue(
                overheadPerPage <= 1024,
                "PDF overhead was $overheadPerPage B/page (ratio $ratio on ${sourceBytes / PAGE_COUNT} B pages)",
            )
            assertTrue(String(pdf, Charsets.ISO_8859_1).startsWith("%PDF-"), "output is not a PDF")
            assertTrue(
                String(pdf, Charsets.ISO_8859_1).trimEnd().endsWith("%%EOF"),
                "the document was not terminated",
            )
            assertEquals(
                PAGE_COUNT,
                Regex("""/Count\s+(\d+)""")
                    .find(String(pdf, Charsets.ISO_8859_1))
                    ?.groupValues
                    ?.get(1)
                    ?.toInt(),
            )
        }

    @Test
    fun `camera-format pages reach the pdf without being re-encoded`() =
        runTest {
            val session = container.repository.createSession("Fidelity")
            val original = pageJpeg(0)
            container.ingestor.ingest(session.id, original)

            val page = requireNotNull(container.repository.getSession(session.id)).pages.single()
            val stored = container.repository.pageFile(session.id, page)

            // The file on disk is the capture, byte for byte.
            assertContentEquals(original, stored.readBytes())

            val output = ByteArrayOutputStream()
            container.exporter.export(listOf(ExportPage(stored, page.geometry)), output)

            // ...and so are the bytes inside the PDF.
            assertTrue(
                output.toByteArray().containsSubsequence(original),
                "the original JPEG should appear verbatim in the exported PDF",
            )
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

    private companion object {
        const val PAGE_COUNT = 120
        const val PAGE_WIDTH = 480
        const val PAGE_HEIGHT = 640
    }
}
