package dev.bookscanner.engine.production

/**
 * Test-only structural PDF reader. Deliberately minimal: it verifies the
 * things the exporter contract promises (it is a PDF, it has the right number
 * of pages, it is terminated) without pulling a PDF library into the test
 * classpath just to assert them.
 */
internal class MinimalPdfReader(
    private val bytes: ByteArray,
) {
    private val text = String(bytes, Charsets.ISO_8859_1)

    val isPdf: Boolean get() = text.startsWith("%PDF-")

    val version: String? get() = PDF_HEADER.find(text)?.groupValues?.get(1)

    /** A well-formed PDF ends with an EOF marker after its xref table. */
    val hasTrailer: Boolean
        get() = text.contains("startxref") && text.trimEnd().endsWith("%%EOF")

    val hasXref: Boolean get() = text.contains("xref") || text.contains("/XRef")

    /**
     * Page count from the page tree's `/Count`, falling back to counting
     * `/Type /Page` objects for writers that omit it.
     */
    val pageCount: Int
        get() =
            COUNT
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: PAGE_OBJECT.findAll(text).count()

    /** Number of embedded image XObjects — one per page for an image-only PDF. */
    val imageCount: Int get() = IMAGE_SUBTYPE.findAll(text).count()

    /** Image filters used, e.g. `DCTDecode` (JPEG) or `FlateDecode` (zlib). */
    val imageFilters: Set<String>
        get() = FILTER.findAll(text).map { it.groupValues[1] }.toSet()

    val byteSize: Int get() = bytes.size

    private companion object {
        val PDF_HEADER = Regex("""^%PDF-(\d+\.\d+)""")
        val COUNT = Regex("""/Count\s+(\d+)""")
        val PAGE_OBJECT = Regex("""/Type\s*/Page[^s]""")
        val IMAGE_SUBTYPE = Regex("""/Subtype\s*/Image""")
        val FILTER = Regex("""/Filter\s*/(\w+)""")
    }
}
