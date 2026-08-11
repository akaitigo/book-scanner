package dev.bookscanner.app

import android.content.Context
import android.graphics.BitmapFactory
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
import dev.bookscanner.engine.production.toGrayscale
import dev.bookscanner.vision.ContourPageDetector
import dev.bookscanner.vision.PageSignature
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

    /**
     * Fingerprints captured JPEG bytes so a repeat of the previous page can be
     * recognised. Decoding small is deliberate: the signature is 64x48, so
     * anything beyond a few hundred pixels is wasted work on the capture path.
     */
    fun pageSignatureOf(bytes: ByteArray): PageSignature? =
        runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = SIGNATURE_SAMPLE_SIZE }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            try {
                PageSignature.of(bitmap.toGrayscale())
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()

    companion object {
        /** 4080 px / 16 ≈ 255 px, comfortably above the 64-wide signature. */
        private const val SIGNATURE_SAMPLE_SIZE = 16

        /**
         * Sessions live in app-private storage: no permission is needed and
         * scans are not exposed to other apps or to media scanners.
         */
        fun defaultStorageRoot(context: Context): File = File(context.filesDir, "sessions")
    }
}
