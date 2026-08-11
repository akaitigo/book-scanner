package dev.bookscanner.app

import android.content.Context
import dev.bookscanner.core.contracts.PageDetector
import dev.bookscanner.core.contracts.PageImageNormalizer
import dev.bookscanner.core.contracts.PageTransformer
import dev.bookscanner.core.contracts.PdfExporter
import dev.bookscanner.core.contracts.ScanRepository
import dev.bookscanner.core.session.FileScanRepository
import dev.bookscanner.core.session.PageIngestor
import dev.bookscanner.engine.production.AndroidPageDetection
import dev.bookscanner.engine.production.AndroidPageImageNormalizer
import dev.bookscanner.engine.production.BitmapPageTransformer
import dev.bookscanner.engine.production.JpegPdfExporter
import dev.bookscanner.vision.ContourPageDetector
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

    /**
     * Page detection. The detector is pure JVM (ADR-0008) and this wraps it
     * with the Android decode; swapping in another engine is a change here and
     * nowhere else.
     */
    val detector: PageDetector = ContourPageDetector()

    val pageDetection = AndroidPageDetection(detector = detector)

    val ingestor = PageIngestor(store = repository, normalizer = normalizer)

    companion object {
        /**
         * Sessions live in app-private storage: no permission is needed and
         * scans are not exposed to other apps or to media scanners.
         */
        fun defaultStorageRoot(context: Context): File = File(context.filesDir, "sessions")
    }
}
