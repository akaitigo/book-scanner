package dev.bookscanner.core.contracts

import java.io.File
import java.io.OutputStream

/**
 * One page to be rendered into the output PDF: the original image file plus
 * the non-destructive geometry to apply.
 */
data class ExportPage(
    val imageFile: File,
    val geometry: PageGeometry = PageGeometry.IDENTITY,
)

/**
 * Renders an ordered list of pages into a single PDF written to [output].
 *
 * Contract:
 * - pages appear in list order;
 * - implementations must stream — at most one page's full-resolution pixel
 *   data may be resident in memory at a time (book-scale sessions);
 * - [onProgress] is invoked after each page with (pagesDone, totalPages);
 * - cooperative cancellation: implementations must check for coroutine
 *   cancellation between pages and stop writing promptly.
 */
interface PdfExporter {
    val engine: EngineId

    suspend fun export(
        pages: List<ExportPage>,
        output: OutputStream,
        onProgress: (pagesDone: Int, totalPages: Int) -> Unit = { _, _ -> },
    )
}
