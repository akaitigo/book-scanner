package dev.bookscanner.core.contracts

import java.io.File
import java.io.OutputStream

/**
 * Applies [PageGeometry] to a page image, producing a JPEG. Pixel work is an
 * engine concern; this contract stays platform-neutral (files and streams).
 */
interface PageTransformer {
    val engine: EngineId

    /**
     * Decode [input], apply [geometry] (rotation, then crop in rotated
     * coordinates), optionally downscale so the longest edge is at most
     * [maxDimension] pixels, and write JPEG bytes to [output].
     */
    suspend fun transform(
        input: File,
        geometry: PageGeometry,
        output: OutputStream,
        maxDimension: Int? = null,
        jpegQuality: Int = 90,
    )
}
