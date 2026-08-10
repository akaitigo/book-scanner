package dev.bookscanner.engine.production

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the graphics capabilities of this project's JVM test environment,
 * because ADR-0004/ADR-0007 were decided on them.
 *
 * The build hosts have no `/dev/kvm`, so an emulator is not available and
 * these JVM tests are the only place image behaviour can be measured at all.
 *
 * The [PdfDocument] assertion is deliberately inverted: it asserts that the
 * platform PDF API does *not* work here. If a future Robolectric gains
 * support, this test fails — which is exactly the signal ADR-0007's "Revisit
 * When" asks for, rather than a silent assumption growing stale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphicsCapabilityTest {
    @Test
    fun `native graphics mode produces real pixels`() {
        val bitmap = TestImages.quadrantBitmap(width = 4, height = 4)

        assertEquals(TestImages.TOP_LEFT, bitmap.getPixel(0, 0))
        assertEquals(TestImages.TOP_RIGHT, bitmap.getPixel(3, 0))
        assertEquals(TestImages.BOTTOM_LEFT, bitmap.getPixel(0, 3))
        assertEquals(TestImages.BOTTOM_RIGHT, bitmap.getPixel(3, 3))
    }

    @Test
    fun `jpeg encoding and decoding are real`() {
        val bitmap = TestImages.noisyBitmap(64, 64)
        val out = ByteArrayOutputStream()

        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
        assertTrue(out.size() > 500, "flat output would mean a stubbed encoder, got ${out.size()} B")

        val decoded = assertNotNull(BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()))
        assertEquals(64, decoded.width)
        assertEquals(64, decoded.height)
    }

    @Test
    fun `platform PdfDocument is unavailable here - tripwire for ADR-0007`() {
        val document = PdfDocument()

        // The native document handle is never allocated, so the very first
        // call fails. Measuring the platform exporter is therefore impossible
        // in CI — see docs/adr/0007-pdf-export-jpeg-passthrough.md.
        val error =
            assertFailsWith<IllegalStateException> {
                document.startPage(PdfDocument.PageInfo.Builder(200, 100, 1).create())
            }
        assertTrue(
            error.message.orEmpty().contains("closed"),
            "unexpected failure mode: ${error.message}",
        )
    }
}
