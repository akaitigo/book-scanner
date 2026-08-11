package dev.bookscanner.vision

import dev.bookscanner.core.contracts.EngineId
import dev.bookscanner.core.contracts.GrayscaleImage
import dev.bookscanner.core.contracts.PageDetection
import dev.bookscanner.core.contracts.PageDetector
import kotlin.math.max

/**
 * Finds a page boundary with a hand-written pipeline
 * ([ADR-0008](../../../../../../docs/adr/0008-page-detection.md)).
 *
 * ```
 * downscale → blur → Sobel → non-maximum suppression → Otsu threshold
 *          → Hough lines → quadrilateral selection
 * ```
 *
 * Every stage is a pure function over a [GrayscaleImage], so the whole
 * detector runs in CI on synthetic pages with known corners — which is the
 * reason it exists rather than a CV dependency this project cannot execute.
 *
 * Reports [PageDetection.NOT_FOUND] rather than throwing: a page on a busy
 * background may genuinely not be findable, and manual crop is always
 * available.
 */
class ContourPageDetector(
    /**
     * Longest edge the detection pipeline works at. Page edges are large
     * structures; running at ~600 px instead of full resolution is ~50x less
     * work and suppresses paper texture that would otherwise vote in the Hough
     * accumulator.
     */
    private val workingLongestEdge: Int = DEFAULT_WORKING_EDGE,
    private val blurRadius: Int = DEFAULT_BLUR_RADIUS,
    /** Below this score the boundary is reported but flagged low-confidence. */
    private val minScore: Float = DEFAULT_MIN_SCORE,
) : PageDetector {
    override val engine: EngineId = EngineId.FROM_SCRATCH

    override fun detect(image: GrayscaleImage): PageDetection {
        val scaled = ImageOps.downscale(image, ImageOps.scaleFactorFor(image, workingLongestEdge))
        val blurred = ImageOps.gaussianBlur(scaled, blurRadius)
        val gradients = ImageOps.sobel(blurred).nonMaximumSuppressed()

        // Guard on the evidence, not on the threshold. A zero *threshold* is a
        // legitimate answer; a zero maximum gradient means there is genuinely
        // no structure in the frame.
        if ((gradients.magnitude.maxOrNull() ?: 0f) <= MIN_GRADIENT) return PageDetection.NOT_FOUND

        val threshold = ImageOps.otsuThreshold(gradients.magnitude)
        val edges = gradients.edgePointsWithHysteresis(high = max(threshold, MIN_GRADIENT))
        if (edges.size < MIN_EDGE_POINTS) return PageDetection.NOT_FOUND

        val lines = HoughLines.detect(edges, scaled.width, scaled.height)
        val quadrilateral =
            QuadrilateralFinder.findBest(lines, scaled.width, scaled.height)
                ?: return PageDetection.NOT_FOUND

        // The boundary is normalized, so it needs no rescaling back to the
        // original resolution — that is the point of working in 0..1.
        return PageDetection(
            boundary = quadrilateral.boundary,
            confidence = quadrilateral.score.coerceIn(0f, 1f),
        )
    }

    /** True when [detection] is trustworthy enough to apply without asking. */
    fun isConfident(detection: PageDetection): Boolean = detection.found && detection.confidence >= minScore

    private companion object {
        const val DEFAULT_WORKING_EDGE = 600
        const val DEFAULT_BLUR_RADIUS = 2
        const val DEFAULT_MIN_SCORE = 0.55f

        /** Fewer edge pixels than this is an empty or hopelessly flat frame. */
        const val MIN_EDGE_POINTS = 200

        /** Below this magnitude a "gradient" is rounding noise, not an edge. */
        const val MIN_GRADIENT = 1f
    }
}
