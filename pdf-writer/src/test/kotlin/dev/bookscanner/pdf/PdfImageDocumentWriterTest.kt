package dev.bookscanner.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verification strategy: the assertions go through Apache PDFBox, an
 * independent PDF implementation, rather than through our own reader. A
 * hand-written PDF that only our own parser accepts would prove nothing about
 * whether real readers can open it.
 */
class PdfImageDocumentWriterTest {
    private fun writePdf(
        policy: PageSizePolicy = PageSizePolicy.FitLongestEdge(),
        block: PdfImageDocumentWriter.() -> Unit,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        PdfImageDocumentWriter(out, policy).use { writer ->
            writer.block()
            writer.finish()
        }
        return out.toByteArray()
    }

    @Test
    fun `output opens in an independent pdf implementation`() {
        val bytes = writePdf { addPage(JpegFixtures.solid()) }

        Loader.loadPDF(bytes).use { document ->
            assertEquals(1, document.numberOfPages)
            assertEquals("1.4", document.version.toString())
        }
    }

    @Test
    fun `page count and order are preserved`() {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE)
        val bytes =
            writePdf {
                colors.forEach { addPage(JpegFixtures.solid(color = it)) }
            }

        Loader.loadPDF(bytes).use { document ->
            assertEquals(3, document.numberOfPages)
            val renderer = PDFRenderer(document)
            colors.forEachIndexed { index, expected ->
                val rendered = renderer.renderImage(index)
                val actual = Color(rendered.getRGB(rendered.width / 2, rendered.height / 2))
                assertTrue(
                    actual.closeTo(expected),
                    "page $index rendered as $actual, expected close to $expected",
                )
            }
        }
    }

    @Test
    fun `embedded stream is byte-identical to the source jpeg`() {
        // This is the whole point of the passthrough design: no re-encode, so
        // the scan in the PDF is the scan the camera produced.
        val source = JpegFixtures.noisyPage(width = 300, height = 400)
        val bytes = writePdf { addPage(source) }

        Loader.loadPDF(bytes).use { document ->
            val resources = document.getPage(0).resources
            val name = requireNotNull(resources.xObjectNames.firstOrNull())
            val image = resources.getXObject(name) as PDImageXObject
            assertEquals("jpg", image.suffix, "stream should be DCTDecode-filtered")
            // Raw, not decoded: the assertion is that the *compressed* bytes
            // survived untouched.
            val raw =
                image.stream.cosObject
                    .createRawInputStream()
                    .use { it.readBytes() }
            assertContentEquals(source, raw, "embedded JPEG must be the original bytes")
        }
    }

    @Test
    fun `output size stays within a small overhead of the source jpegs`() {
        // ADR-0004's acceptance gate. Passthrough makes this structural rather
        // than a hope: overhead is object dictionaries and the xref table.
        val sources = (1..10).map { JpegFixtures.noisyPage(seed = it.toLong()) }
        val bytes = writePdf { sources.forEach { addPage(it) } }

        val sourceTotal = sources.sumOf { it.size }
        val ratio = bytes.size.toDouble() / sourceTotal
        // Printed so CI logs carry the measurement, not just a pass/fail —
        // AGENTS.md §10 forbids performance claims without numbers.
        println("MEASURE pdf-size-ratio pages=${sources.size} source=${sourceTotal}B pdf=${bytes.size}B ratio=$ratio")
        assertTrue(
            ratio <= 1.05,
            "PDF was ${bytes.size} B for $sourceTotal B of JPEG (ratio $ratio); budget is 1.05",
        )
    }

    @Test
    fun `media box preserves the image aspect ratio`() {
        val bytes = writePdf { addPage(JpegFixtures.solid(width = 400, height = 200)) }

        Loader.loadPDF(bytes).use { document ->
            val box = document.getPage(0).mediaBox
            assertEquals(842f, box.width, 0.5f, "longest edge should fit A4's long edge")
            assertEquals(421f, box.height, 0.5f)
        }
    }

    @Test
    fun `fixed dpi policy sizes pages by resolution`() {
        val bytes =
            writePdf(PageSizePolicy.FixedDpi(dpi = 100f)) {
                addPage(JpegFixtures.solid(width = 200, height = 100))
            }

        Loader.loadPDF(bytes).use { document ->
            val box = document.getPage(0).mediaBox
            assertEquals(144f, box.width, 0.5f, "200 px at 100 dpi = 2 inch = 144 pt")
            assertEquals(72f, box.height, 0.5f)
        }
    }

    @Test
    fun `rotation is recorded as a page attribute`() {
        val bytes =
            writePdf {
                addPage(JpegFixtures.solid(), rotationDegrees = 0)
                addPage(JpegFixtures.solid(), rotationDegrees = 90)
                addPage(JpegFixtures.solid(), rotationDegrees = 270)
                addPage(JpegFixtures.solid(), rotationDegrees = -90)
            }

        Loader.loadPDF(bytes).use { document ->
            assertEquals(0, document.getPage(0).rotation)
            assertEquals(90, document.getPage(1).rotation)
            assertEquals(270, document.getPage(2).rotation)
            assertEquals(270, document.getPage(3).rotation, "-90 should normalize to 270")
        }
    }

    @Test
    fun `rotated page swaps rendered dimensions`() {
        val bytes =
            writePdf {
                addPage(JpegFixtures.solid(width = 400, height = 200), rotationDegrees = 90)
            }

        Loader.loadPDF(bytes).use { document ->
            val rendered = PDFRenderer(document).renderImage(0)
            assertTrue(
                rendered.height > rendered.width,
                "a landscape page rotated 90 deg should render portrait, got ${rendered.width}x${rendered.height}",
            )
        }
    }

    @Test
    fun `grayscale jpeg is declared as DeviceGray`() {
        val bytes = writePdf { addPage(JpegFixtures.grayscale()) }

        Loader.loadPDF(bytes).use { document ->
            val resources = document.getPage(0).resources
            val name = requireNotNull(resources.xObjectNames.firstOrNull())
            val image = resources.getXObject(name) as PDImageXObject
            assertEquals("DeviceGray", image.colorSpace.name)
        }
    }

    @Test
    fun `rejects progressive jpeg rather than writing an unreadable page`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                writePdf { addPage(JpegFixtures.solid(progressive = true)) }
            }
        assertTrue(error.message.orEmpty().contains("not embeddable"), "got: ${error.message}")
    }

    @Test
    fun `rejects non-jpeg input`() {
        assertFailsWith<java.io.IOException> {
            writePdf { addPage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }
        }
    }

    @Test
    fun `rejects a document with no pages`() {
        assertFailsWith<IllegalStateException> { writePdf { } }
    }

    @Test
    fun `rejects rotation that is not a multiple of 90`() {
        assertFailsWith<IllegalArgumentException> {
            writePdf { addPage(JpegFixtures.solid(), rotationDegrees = 45) }
        }
    }

    @Test
    fun `rejects pages added after finish`() {
        val out = ByteArrayOutputStream()
        val writer = PdfImageDocumentWriter(out)
        writer.addPage(JpegFixtures.solid())
        writer.finish()
        assertFailsWith<IllegalStateException> { writer.addPage(JpegFixtures.solid()) }
    }

    @Test
    fun `writes forward only and never seeks`() {
        // SAF's CreateDocument stream cannot be rewound, so the writer must
        // produce correct xref offsets on a single forward pass.
        val out = ForwardOnlyOutputStream()
        PdfImageDocumentWriter(out).use { writer ->
            repeat(3) { writer.addPage(JpegFixtures.solid()) }
            writer.finish()
        }
        Loader.loadPDF(out.toByteArray()).use { document ->
            assertEquals(3, document.numberOfPages)
        }
    }

    @Test
    fun `book scale document stays valid and ordered`() {
        val pageCount = 120
        val bytes =
            writePdf {
                repeat(pageCount) { index ->
                    addPage(JpegFixtures.solid(width = 120, height = 160, color = index.marker()))
                }
            }

        Loader.loadPDF(bytes).use { document ->
            assertEquals(pageCount, document.numberOfPages)
            val renderer = PDFRenderer(document)
            listOf(0, 1, pageCount / 2, pageCount - 1).forEach { index ->
                val rendered = renderer.renderImage(index)
                val actual = Color(rendered.getRGB(rendered.width / 2, rendered.height / 2))
                assertTrue(
                    actual.closeTo(index.marker()),
                    "page $index rendered as $actual, expected close to ${index.marker()}",
                )
            }
        }
    }

    /** Distinct-per-page colour so rendered output identifies its page index. */
    private fun Int.marker(): Color = Color((this * 2) % 256, (this * 5) % 256, (this * 11) % 256)

    /** JPEG is lossy; exact equality would be flaky. */
    private fun Color.closeTo(
        other: Color,
        tolerance: Int = 12,
    ): Boolean =
        abs(red - other.red) <= tolerance &&
            abs(green - other.green) <= tolerance &&
            abs(blue - other.blue) <= tolerance

    /** Accepts writes and nothing else — no seek, no rewind, no random access. */
    private class ForwardOnlyOutputStream : OutputStream() {
        private val buffer = ByteArrayOutputStream()

        override fun write(b: Int) = buffer.write(b)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = buffer.write(b, off, len)

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }
}
