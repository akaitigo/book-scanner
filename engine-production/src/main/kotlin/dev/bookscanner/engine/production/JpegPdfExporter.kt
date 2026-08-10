package dev.bookscanner.engine.production

import dev.bookscanner.core.contracts.EngineId
import dev.bookscanner.core.contracts.ExportPage
import dev.bookscanner.core.contracts.PageTransformer
import dev.bookscanner.core.contracts.PdfExporter
import dev.bookscanner.pdf.PageSizePolicy
import dev.bookscanner.pdf.PdfImageDocumentWriter
import dev.bookscanner.pdf.parseJpegMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Production [PdfExporter] (ADR-0007).
 *
 * Two paths per page:
 *
 * - **Passthrough** — the page has no crop and its stored bytes are a baseline
 *   JPEG, so they are embedded verbatim as `/DCTDecode` and any rotation is
 *   expressed as the PDF page's `/Rotate` attribute. No decode, no re-encode:
 *   the exported page is bit-for-bit the captured scan.
 * - **Re-encode** — the page is cropped (or its bytes are not embeddable), so
 *   pixels are rendered by [transformer] and the result embedded. Rotation is
 *   baked into those pixels, so `/Rotate` MUST stay 0 for these pages;
 *   applying both would rotate twice.
 *
 * Memory: one page's bytes at a time, and the writer streams straight to
 * [OutputStream] without buffering the document.
 */
class JpegPdfExporter(
    private val transformer: PageTransformer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pageSizePolicy: PageSizePolicy = PageSizePolicy.FitLongestEdge(),
    /** Longest edge for pages that must be re-encoded; null keeps full size. */
    private val maxReencodedDimension: Int? = null,
    private val reencodeQuality: Int = DEFAULT_REENCODE_QUALITY,
) : PdfExporter {
    override val engine: EngineId = EngineId.PRODUCTION

    /** Per-export counters, useful for benchmarking the passthrough hit rate. */
    data class Stats(
        val passthroughPages: Int,
        val reencodedPages: Int,
    )

    @Volatile
    var lastExportStats: Stats = Stats(0, 0)
        private set

    override suspend fun export(
        pages: List<ExportPage>,
        output: OutputStream,
        onProgress: (pagesDone: Int, totalPages: Int) -> Unit,
    ) {
        require(pages.isNotEmpty()) { "Cannot export a PDF with no pages" }

        withContext(ioDispatcher) {
            var passthrough = 0
            var reencoded = 0
            PdfImageDocumentWriter(output, pageSizePolicy).use { writer ->
                pages.forEachIndexed { index, page ->
                    coroutineContext.ensureActive()
                    if (writePage(page, writer)) passthrough++ else reencoded++
                    onProgress(index + 1, pages.size)
                }
                coroutineContext.ensureActive()
                writer.finish()
            }
            lastExportStats = Stats(passthrough, reencoded)
        }
    }

    /** @return true when the page was embedded without re-encoding. */
    private suspend fun writePage(
        page: ExportPage,
        writer: PdfImageDocumentWriter,
    ): Boolean {
        if (page.geometry.crop == null) {
            val bytes = page.imageFile.readBytes()
            if (parseJpegMetadata(bytes)?.isEmbeddable == true) {
                writer.addPage(bytes, rotationDegrees = page.geometry.rotationDegrees)
                return true
            }
        }
        val rendered =
            ByteArrayOutputStream()
                .also { buffer ->
                    transformer.transform(
                        input = page.imageFile,
                        geometry = page.geometry,
                        output = buffer,
                        maxDimension = maxReencodedDimension,
                        jpegQuality = reencodeQuality,
                    )
                }.toByteArray()
        // Rotation is already in the pixels; do not also set /Rotate.
        writer.addPage(rendered, rotationDegrees = 0)
        return false
    }

    private companion object {
        const val DEFAULT_REENCODE_QUALITY = 92
    }
}
