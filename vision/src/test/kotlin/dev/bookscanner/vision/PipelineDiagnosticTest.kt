package dev.bookscanner.vision

import org.junit.Test

/**
 * Prints what each stage produced. Kept because "detection failed" is not a
 * diagnosis: the pipeline has six stages and any of them can be the one that
 * emptied the pot.
 */
class PipelineDiagnosticTest {
    private fun report(
        label: String,
        image: dev.bookscanner.core.contracts.GrayscaleImage,
    ) {
        val scaled = ImageOps.downscale(image, ImageOps.scaleFactorFor(image, 600))
        val blurred = ImageOps.gaussianBlur(scaled, 2)
        val gradients = ImageOps.sobel(blurred).nonMaximumSuppressed()
        val threshold = ImageOps.otsuThreshold(gradients.magnitude)
        val edges = gradients.edgePointsWithHysteresis(high = maxOf(threshold, 1f))
        val lines = HoughLines.detect(edges, scaled.width, scaled.height)
        val horizontal = lines.count { Math.toDegrees(it.theta.toDouble()) in 45.0..135.0 }
        val quad = QuadrilateralFinder.findBest(lines, scaled.width, scaled.height)

        println(
            "DIAG $label size=${scaled.width}x${scaled.height} maxGrad=${gradients.magnitude.max()} " +
                "threshold=$threshold edges=${edges.size} lines=${lines.size} " +
                "h=$horizontal v=${lines.size - horizontal} quad=${quad?.score}",
        )
    }

    @Test
    fun `report each stage for the failing and passing cases`() {
        report("plain", SyntheticPages.page())
        report("perspective", SyntheticPages.page(corners = SyntheticPages.perspectiveCorners))
        report("noisy", SyntheticPages.page(noise = 12))
        report("cluttered", SyntheticPages.page(backgroundClutter = true))
        report("lowContrast", SyntheticPages.page(pageLuminance = 200, backgroundLuminance = 165))
    }
}
