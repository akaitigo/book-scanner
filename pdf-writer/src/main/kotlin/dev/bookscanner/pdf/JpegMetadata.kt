package dev.bookscanner.pdf

/**
 * The subset of JPEG header information a PDF writer needs in order to embed
 * the compressed bytes directly (`/DCTDecode`), without decoding them.
 */
data class JpegMetadata(
    val widthPx: Int,
    val heightPx: Int,
    val components: Int,
    val bitsPerComponent: Int,
    /** SOF marker code, e.g. 0xC0 baseline, 0xC2 progressive. */
    val encodingMarker: Int,
) {
    /**
     * Whether these bytes can be embedded verbatim.
     *
     * PDF's `/DCTDecode` filter is specified against baseline and extended
     * sequential JPEG. Progressive JPEG (SOF2) renders in some viewers and not
     * others, and 4-component (CMYK/YCCK) needs an Adobe transform and a
     * `/Decode` array to display correctly — neither is worth risking on a
     * user's only copy of a scan, so both route through re-encoding instead.
     */
    val isEmbeddable: Boolean
        get() =
            encodingMarker in EMBEDDABLE_MARKERS &&
                components in setOf(1, 3) &&
                bitsPerComponent == 8

    val colorSpace: PdfColorSpace
        get() =
            when (components) {
                1 -> PdfColorSpace.DEVICE_GRAY
                3 -> PdfColorSpace.DEVICE_RGB
                else -> error("Unsupported component count $components; check isEmbeddable first")
            }

    companion object {
        /** SOF0 (baseline) and SOF1 (extended sequential). */
        private val EMBEDDABLE_MARKERS = setOf(0xC0, 0xC1)
    }
}

enum class PdfColorSpace(
    internal val pdfName: String,
) {
    DEVICE_GRAY("/DeviceGray"),
    DEVICE_RGB("/DeviceRGB"),
}

/**
 * Reads JPEG frame headers. Returns null when [bytes] is not a JPEG or has no
 * readable frame header — callers treat that as "not embeddable" and fall back
 * to re-encoding rather than failing an export.
 */
fun parseJpegMetadata(bytes: ByteArray): JpegMetadata? {
    if (bytes.size < 4) return null
    if (bytes.u8(0) != 0xFF || bytes.u8(1) != 0xD8) return null

    var offset = 2
    while (offset + 3 < bytes.size) {
        if (bytes.u8(offset) != 0xFF) {
            // Not at a marker boundary: resynchronize rather than give up, as
            // some encoders pad with 0xFF fill bytes between segments.
            offset++
            continue
        }
        var marker = bytes.u8(offset + 1)
        // Runs of 0xFF are legal padding before the marker code itself.
        var markerOffset = offset + 1
        while (marker == 0xFF && markerOffset + 1 < bytes.size) {
            markerOffset++
            marker = bytes.u8(markerOffset)
        }
        offset = markerOffset + 1

        when (marker) {
            // Standalone markers carry no length field.
            0xD8, 0x01, in 0xD0..0xD7 -> continue

            // Start of scan: compressed data follows, no more headers of interest.
            0xDA, 0xD9 -> return null
        }

        if (offset + 1 >= bytes.size) return null
        val segmentLength = bytes.u16(offset)
        if (segmentLength < 2 || offset + segmentLength > bytes.size) return null

        if (marker in SOF_MARKERS) {
            // SOF payload: length(2) precision(1) height(2) width(2) components(1)
            if (offset + 7 >= bytes.size) return null
            return JpegMetadata(
                heightPx = bytes.u16(offset + 3),
                widthPx = bytes.u16(offset + 5),
                components = bytes.u8(offset + 7),
                bitsPerComponent = bytes.u8(offset + 2),
                encodingMarker = marker,
            )
        }
        offset += segmentLength
    }
    return null
}

/** All SOF markers; 0xC4/0xC8/0xCC are DHT/JPG/DAC and are not frame headers. */
private val SOF_MARKERS = ((0xC0..0xCF) - setOf(0xC4, 0xC8, 0xCC)).toSet()

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

private fun ByteArray.u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)
