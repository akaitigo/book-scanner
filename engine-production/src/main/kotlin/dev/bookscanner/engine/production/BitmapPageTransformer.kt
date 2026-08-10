package dev.bookscanner.engine.production

import android.graphics.Bitmap
import dev.bookscanner.core.contracts.EngineId
import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.core.contracts.PageTransformer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Production [PageTransformer]: renders a page's geometry to a JPEG.
 *
 * This is the preview/thumbnail path. PDF export does NOT route through it —
 * [PdfDocumentExporter] decodes straight to a bitmap, avoiding a second lossy
 * encode per page.
 */
class BitmapPageTransformer(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PageTransformer {
    override val engine: EngineId = EngineId.PRODUCTION

    override suspend fun transform(
        input: File,
        geometry: PageGeometry,
        output: OutputStream,
        maxDimension: Int?,
        jpegQuality: Int,
    ) {
        require(jpegQuality in 1..100) { "jpegQuality must be in 1..100, got $jpegQuality" }
        withContext(ioDispatcher) {
            val bitmap = PageImageDecoder.decode(input, geometry, maxDimension)
            try {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)) {
                    throw java.io.IOException("JPEG encoding failed for $input")
                }
                output.flush()
            } finally {
                bitmap.recycle()
            }
        }
    }
}
