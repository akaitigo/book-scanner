package dev.bookscanner.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JpegMetadataTest {
    @Test
    fun `reads dimensions and components from a baseline jpeg`() {
        val metadata = requireNotNull(parseJpegMetadata(JpegFixtures.solid(width = 321, height = 123)))
        assertEquals(321, metadata.widthPx)
        assertEquals(123, metadata.heightPx)
        assertEquals(3, metadata.components)
        assertEquals(8, metadata.bitsPerComponent)
        assertEquals(0xC0, metadata.encodingMarker)
        assertTrue(metadata.isEmbeddable)
        assertEquals(PdfColorSpace.DEVICE_RGB, metadata.colorSpace)
    }

    @Test
    fun `grayscale jpeg maps to DeviceGray`() {
        val metadata = requireNotNull(parseJpegMetadata(JpegFixtures.grayscale()))
        assertEquals(1, metadata.components)
        assertTrue(metadata.isEmbeddable)
        assertEquals(PdfColorSpace.DEVICE_GRAY, metadata.colorSpace)
    }

    @Test
    fun `progressive jpeg is parsed but not embeddable`() {
        val metadata = requireNotNull(parseJpegMetadata(JpegFixtures.solid(progressive = true)))
        assertEquals(0xC2, metadata.encodingMarker, "expected SOF2 (progressive)")
        assertFalse(metadata.isEmbeddable, "progressive JPEG must not be embedded verbatim")
    }

    @Test
    fun `non jpeg input returns null`() {
        assertNull(parseJpegMetadata(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertNull(parseJpegMetadata(ByteArray(0)))
        assertNull(parseJpegMetadata("not an image at all".toByteArray()))
    }

    @Test
    fun `truncated jpeg returns null instead of throwing`() {
        val full = JpegFixtures.solid()
        assertNull(parseJpegMetadata(full.copyOfRange(0, 8)))
        assertNull(parseJpegMetadata(full.copyOfRange(0, 2)))
    }

    @Test
    fun `parser survives a jpeg with an exif segment before the frame header`() {
        // APP1/EXIF sits between SOI and SOF in camera output; the parser must
        // skip variable-length segments rather than assume a fixed layout.
        val original = JpegFixtures.solid(width = 64, height = 48)
        val exifSegment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x10) + ByteArray(14)
        val withExif = original.copyOfRange(0, 2) + exifSegment + original.copyOfRange(2, original.size)

        val metadata = requireNotNull(parseJpegMetadata(withExif))
        assertEquals(64, metadata.widthPx)
        assertEquals(48, metadata.heightPx)
    }
}
