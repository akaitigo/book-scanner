package dev.bookscanner.pdf

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Writes a valid image-only PDF by embedding baseline JPEG bytes verbatim as
 * `/DCTDecode` image XObjects — no decode, no re-encode, so the bytes the
 * camera produced are the bytes the reader sees.
 *
 * Written for a **forward-only** stream (SAF `CreateDocument` gives no way to
 * seek back): every object's byte offset is recorded as it is emitted, and the
 * cross-reference table is written from that table at the end. Nothing is
 * buffered beyond the current page, so peak memory is one page's JPEG.
 *
 * PDF object layout: object 1 is the page tree and object 2 the catalog, both
 * emitted last — object numbers are independent of file order, so reserving
 * them up front lets each page reference `/Parent 1 0 R` while it is written.
 */
class PdfImageDocumentWriter(
    output: OutputStream,
    private val pageSizePolicy: PageSizePolicy = PageSizePolicy.FitLongestEdge(),
) : Closeable {
    private val out = CountingOutputStream(output)

    /** Byte offset of each object, indexed by object number - 1. */
    private val objectOffsets = mutableListOf<Long>()
    private val pageObjectNumbers = mutableListOf<Int>()

    private var nextObjectNumber = FIRST_PAGE_OBJECT
    private var started = false
    private var finished = false

    /**
     * Appends one page.
     *
     * @param jpeg baseline JPEG bytes; must satisfy [JpegMetadata.isEmbeddable].
     * @param rotationDegrees display rotation applied by the reader via
     *   `/Rotate`. Use 0 when the pixels are already upright — baking rotation
     *   in twice is the easy bug here.
     */
    fun addPage(
        jpeg: ByteArray,
        rotationDegrees: Int = 0,
    ) {
        check(!finished) { "Document already finished" }
        require(rotationDegrees % 90 == 0) {
            "PDF /Rotate must be a multiple of 90, got $rotationDegrees"
        }
        val metadata =
            parseJpegMetadata(jpeg)
                ?: throw IOException("Not a parseable JPEG")
        require(metadata.isEmbeddable) {
            "JPEG is not embeddable (marker=0x${metadata.encodingMarker.toString(16)}, " +
                "components=${metadata.components}); re-encode it before embedding"
        }

        if (!started) {
            writeHeader()
            started = true
        }

        val (widthPt, heightPt) = pageSizePolicy.pageSize(metadata.widthPx, metadata.heightPx)

        val imageObject = writeImageObject(jpeg, metadata)
        val contentObject = writeContentObject(widthPt, heightPt)
        val pageObject =
            writePageObject(
                imageObject = imageObject,
                contentObject = contentObject,
                widthPt = widthPt,
                heightPt = heightPt,
                rotationDegrees = Math.floorMod(rotationDegrees, 360),
            )
        pageObjectNumbers += pageObject
    }

    /** Writes the page tree, catalog, xref table and trailer. */
    fun finish() {
        check(!finished) { "Document already finished" }
        check(pageObjectNumbers.isNotEmpty()) { "Cannot write a PDF with no pages" }
        finished = true

        writeObject(PAGES_OBJECT) {
            val kids = pageObjectNumbers.joinToString(" ") { "$it 0 R" }
            "<< /Type /Pages /Kids [$kids] /Count ${pageObjectNumbers.size} >>"
        }
        writeObject(CATALOG_OBJECT) { "<< /Type /Catalog /Pages $PAGES_OBJECT 0 R >>" }

        val xrefOffset = out.bytesWritten
        val objectCount = objectOffsets.size + 1 // + the mandatory free entry
        out.writeAscii("xref\n0 $objectCount\n")
        out.writeAscii("0000000000 65535 f \n")
        objectOffsets.forEach { offset ->
            out.writeAscii("${offset.toString().padStart(10, '0')} 00000 n \n")
        }
        out.writeAscii("trailer\n<< /Size $objectCount /Root $CATALOG_OBJECT 0 R >>\n")
        out.writeAscii("startxref\n$xrefOffset\n%%EOF\n")
        out.flush()
    }

    override fun close() {
        out.flush()
    }

    // ---- object emission ----

    private fun writeHeader() {
        out.writeAscii("%PDF-1.4\n")
        // Binary comment: marks the file as containing 8-bit data so transfer
        // tools do not mangle the embedded JPEG streams.
        out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))
    }

    private fun writeImageObject(
        jpeg: ByteArray,
        metadata: JpegMetadata,
    ): Int {
        val objectNumber = nextObjectNumber++
        recordOffset(objectNumber)
        out.writeAscii(
            "$objectNumber 0 obj\n" +
                "<< /Type /XObject /Subtype /Image " +
                "/Width ${metadata.widthPx} /Height ${metadata.heightPx} " +
                "/ColorSpace ${metadata.colorSpace.pdfName} " +
                "/BitsPerComponent ${metadata.bitsPerComponent} " +
                "/Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n",
        )
        out.write(jpeg)
        out.writeAscii("\nendstream\nendobj\n")
        return objectNumber
    }

    private fun writeContentObject(
        widthPt: Float,
        heightPt: Float,
    ): Int {
        val objectNumber = nextObjectNumber++
        // The image XObject occupies the unit square; the CTM scales it to the
        // full media box.
        val stream = "q\n${widthPt.pdf()} 0 0 ${heightPt.pdf()} 0 0 cm\n/$IMAGE_RESOURCE_NAME Do\nQ\n"
        val streamBytes = stream.toByteArray(Charsets.US_ASCII)
        recordOffset(objectNumber)
        out.writeAscii("$objectNumber 0 obj\n<< /Length ${streamBytes.size} >>\nstream\n")
        out.write(streamBytes)
        out.writeAscii("endstream\nendobj\n")
        return objectNumber
    }

    private fun writePageObject(
        imageObject: Int,
        contentObject: Int,
        widthPt: Float,
        heightPt: Float,
        rotationDegrees: Int,
    ): Int {
        val objectNumber = nextObjectNumber++
        recordOffset(objectNumber)
        out.writeAscii(
            "$objectNumber 0 obj\n" +
                "<< /Type /Page /Parent $PAGES_OBJECT 0 R " +
                "/MediaBox [0 0 ${widthPt.pdf()} ${heightPt.pdf()}] " +
                "/Rotate $rotationDegrees " +
                "/Resources << /XObject << /$IMAGE_RESOURCE_NAME $imageObject 0 R >> >> " +
                "/Contents $contentObject 0 R >>\nendobj\n",
        )
        return objectNumber
    }

    private fun writeObject(
        objectNumber: Int,
        body: () -> String,
    ) {
        recordOffset(objectNumber)
        out.writeAscii("$objectNumber 0 obj\n${body()}\nendobj\n")
    }

    /**
     * Records the current offset for [objectNumber]. Object 1 and 2 are
     * emitted last but must appear at their own index in the xref table.
     */
    private fun recordOffset(objectNumber: Int) {
        while (objectOffsets.size < objectNumber) {
            objectOffsets += 0L
        }
        objectOffsets[objectNumber - 1] = out.bytesWritten
    }

    private companion object {
        const val PAGES_OBJECT = 1
        const val CATALOG_OBJECT = 2
        const val FIRST_PAGE_OBJECT = 3
        const val IMAGE_RESOURCE_NAME = "Im0"
    }
}

/** Formats a float the way PDF wants it: no exponent, no trailing noise. */
private fun Float.pdf(): String {
    val rounded = (this * 100).roundToInt() / 100.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}

/**
 * Decides the displayed size of a page in PostScript points (1/72 inch).
 *
 * This affects physical/print size only — the embedded image keeps every pixel
 * it had, so page size never costs quality.
 */
sealed interface PageSizePolicy {
    fun pageSize(
        widthPx: Int,
        heightPx: Int,
    ): Pair<Float, Float>

    /**
     * Scales each page so its longest edge equals [longestEdgePt], preserving
     * aspect ratio. Default is A4's long edge, which makes a mixed-resolution
     * scan print at a consistent physical size with no letterboxing.
     */
    data class FitLongestEdge(
        val longestEdgePt: Float = A4_LONG_EDGE_PT,
    ) : PageSizePolicy {
        override fun pageSize(
            widthPx: Int,
            heightPx: Int,
        ): Pair<Float, Float> {
            val longest = max(widthPx, heightPx).toFloat()
            val scale = longestEdgePt / longest
            return max(1f, widthPx * scale) to max(1f, heightPx * scale)
        }
    }

    /** Treats the image as if it were scanned at [dpi]. */
    data class FixedDpi(
        val dpi: Float = 200f,
    ) : PageSizePolicy {
        init {
            require(dpi > 0) { "dpi must be positive" }
        }

        override fun pageSize(
            widthPx: Int,
            heightPx: Int,
        ): Pair<Float, Float> = max(1f, widthPx * POINTS_PER_INCH / dpi) to max(1f, heightPx * POINTS_PER_INCH / dpi)
    }

    companion object {
        const val A4_LONG_EDGE_PT = 842f
        const val POINTS_PER_INCH = 72f
    }
}

/** Tracks byte offsets so the xref table can be written without seeking. */
private class CountingOutputStream(
    private val delegate: OutputStream,
) : OutputStream() {
    var bytesWritten: Long = 0
        private set

    override fun write(b: Int) {
        delegate.write(b)
        bytesWritten++
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        delegate.write(b, off, len)
        bytesWritten += len
    }

    override fun flush() = delegate.flush()

    fun writeAscii(text: String) {
        write(text.toByteArray(Charsets.US_ASCII))
    }
}
