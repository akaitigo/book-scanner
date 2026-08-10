package dev.bookscanner.pdf

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Real JPEG bytes produced on the host JVM. Using an actual encoder (rather
 * than hand-built headers) means the writer is exercised against the same
 * marker layouts real cameras emit.
 */
internal object JpegFixtures {
    /** Solid-colour image; useful for asserting page order after rendering. */
    fun solid(
        width: Int = 160,
        height: Int = 120,
        color: Color = Color.RED,
        progressive: Boolean = false,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return encodeJpeg(image, progressive)
    }

    /** Single-component JPEG, which must map to `/DeviceGray`. */
    fun grayscale(
        width: Int = 160,
        height: Int = 120,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, width, height)
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, width / 2, height / 2)
        graphics.dispose()
        return encodeJpeg(image, progressive = false)
    }

    /**
     * Deterministic paper-like noise. A flat colour compresses to almost
     * nothing, which would make any size comparison meaningless.
     */
    fun noisyPage(
        width: Int = 800,
        height: Int = 1100,
        seed: Long = 7,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        var state = seed or 1L
        for (y in 0 until height) {
            for (x in 0 until width) {
                state = state * 6364136223846793005L + 1442695040888963407L
                val value = ((state ushr 33).toInt() and 0xFF)
                val level = 200 + (value % 56)
                val ink = if (value % 17 == 0) value % 90 else level
                image.setRGB(x, y, Color(ink, ink, ink).rgb)
            }
        }
        return encodeJpeg(image, progressive = false)
    }

    private fun encodeJpeg(
        image: BufferedImage,
        progressive: Boolean,
    ): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val output = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(output).use { stream ->
            writer.output = stream
            val params =
                writer.defaultWriteParam.apply {
                    if (progressive) {
                        progressiveMode = ImageWriteParam.MODE_DEFAULT
                    } else {
                        progressiveMode = ImageWriteParam.MODE_DISABLED
                    }
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.9f
                }
            writer.write(null, IIOImage(image, null, null), params)
        }
        writer.dispose()
        return output.toByteArray()
    }
}
