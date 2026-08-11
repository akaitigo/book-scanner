package dev.bookscanner.app

import android.content.Context
import dev.bookscanner.core.contracts.PageImageNormalizer
import dev.bookscanner.core.contracts.PageTransformer
import dev.bookscanner.core.contracts.PdfExporter
import dev.bookscanner.core.contracts.ScanRepository
import dev.bookscanner.core.session.FileScanRepository
import dev.bookscanner.core.session.PageIngestor
import dev.bookscanner.engine.production.AndroidPageImageNormalizer
import dev.bookscanner.engine.production.BitmapPageTransformer
import dev.bookscanner.engine.production.JpegPdfExporter
import java.io.File

/**
 * The composition root. Everything is wired by hand here (ADR-0006): the graph
 * is small enough that a DI framework would add build cost and indirection
 * without removing any real work.
 *
 * Each engine is referenced through its contract type, so swapping in a
 * From-Scratch implementation later is a change to this file only.
 */
class AppContainer(
    storageRoot: File,
) {
    val repository: ScanRepository = FileScanRepository(root = storageRoot)

    val normalizer: PageImageNormalizer = AndroidPageImageNormalizer()

    val transformer: PageTransformer = BitmapPageTransformer()

    val exporter: PdfExporter = JpegPdfExporter(transformer = transformer)

    val ingestor = PageIngestor(store = repository, normalizer = normalizer)

    companion object {
        /**
         * Sessions live in app-private storage: no permission is needed and
         * scans are not exposed to other apps or to media scanners.
         */
        fun defaultStorageRoot(context: Context): File = File(context.filesDir, "sessions")
    }
}
