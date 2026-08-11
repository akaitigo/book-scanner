package dev.bookscanner.vision

import dev.bookscanner.core.contracts.GrayscaleImage
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Runs the detector over real photographs from a local directory.
 *
 * The images are **not** in this repository: they are photographs of a
 * copyrighted book, and `CONTRIBUTING.md`'s dataset policy says those stay
 * local and only the recipe is committed. Point it at your own with:
 *
 * ```
 * ./gradlew :vision:test --tests '*RealPageHarness*' -Dbookscanner.realPages=/path/to/dir
 * ```
 *
 * The test skips when the directory is absent, so CI is unaffected. It asserts
 * nothing about accuracy — there is no ground truth for a photograph without
 * hand-labelling — and instead prints what was detected so the numbers can be
 * recorded and judged.
 */
class RealPageHarness {
    @Test
    fun `report detection on real photographs`() {
        val directory = System.getProperty("bookscanner.realPages")?.let(::File)
        assumeTrue("no real-page directory supplied", directory != null && directory.isDirectory)

        val detector = ContourPageDetector()
        val files = requireNotNull(directory).listFiles { f -> f.extension.lowercase() == "jpg" }.orEmpty().sorted()

        files.forEach { file ->
            val image = ImageIO.read(file) ?: return@forEach
            val gray = ByteArray(image.width * image.height)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val rgb = image.getRGB(x, y)
                    val red = (rgb shr 16) and 0xFF
                    val green = (rgb shr 8) and 0xFF
                    val blue = rgb and 0xFF
                    gray[y * image.width + x] = ((red * 299 + green * 587 + blue * 114) / 1000).toByte()
                }
            }

            val start = System.nanoTime()
            val detection = detector.detect(GrayscaleImage(image.width, image.height, gray))
            val ms = (System.nanoTime() - start) / 1_000_000

            val corners =
                detection.boundary?.corners?.joinToString(" ") { "(%.3f,%.3f)".format(it.x, it.y) }
                    ?: "none"
            println(
                "REAL ${file.name} ${image.width}x${image.height} ms=$ms " +
                    "found=${detection.found} confidence=%.3f area=%.3f corners=$corners".format(
                        detection.confidence,
                        detection.boundary?.areaFraction ?: 0f,
                    ),
            )
        }
    }
}
