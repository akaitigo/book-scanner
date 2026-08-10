package dev.bookscanner.core.contracts

import java.io.InputStream
import java.io.OutputStream

/**
 * Result of normalizing one incoming image.
 *
 * @param geometry initial geometry for the page. Orientation declared by the
 *   source (EXIF) is folded in here rather than burned into pixels, so the
 *   original bytes can be preserved and the user can still correct it.
 * @param losslessCopy true when the source bytes were stored verbatim — no
 *   decode, no re-encode, no generational quality loss.
 */
data class NormalizedPage(
    val geometry: PageGeometry,
    val losslessCopy: Boolean,
)

/**
 * Converts arbitrary incoming image bytes (camera JPEG, Photo Picker PNG /
 * WebP / HEIC, progressive JPEG) into the one on-disk format the rest of the
 * pipeline is allowed to assume.
 *
 * **Storage invariant established here** — every committed page file is a
 * baseline JPEG whose pixels are stored as captured, and whose EXIF
 * orientation, if any, has been moved into [NormalizedPage.geometry].
 *
 * Two consequences the rest of the codebase depends on:
 * - decoders and exporters MUST NOT apply EXIF orientation again; doing so
 *   would rotate the page twice;
 * - the PDF exporter can embed page bytes directly, because "baseline JPEG"
 *   is exactly what `/DCTDecode` accepts.
 */
interface PageImageNormalizer {
    val engine: EngineId

    /**
     * Reads all of [input] and writes the normalized page image to [output].
     * Implementations must not close either stream.
     */
    suspend fun normalize(
        input: InputStream,
        output: OutputStream,
    ): NormalizedPage
}
