package dev.bookscanner.vision

import dev.bookscanner.core.contracts.NormalizedPoint
import dev.bookscanner.core.contracts.PageBoundary
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Builds a page quadrilateral from detected lines.
 *
 * The problem is not finding lines — it is choosing four of them. A photograph
 * of a book on a desk offers the page edges, the desk edge, the facing page's
 * edge, and text baselines. Selection is therefore scored against what a
 * *page* looks like rather than taking the four strongest.
 */
object QuadrilateralFinder {
    /**
     * @param minAreaFraction reject quadrilaterals smaller than this share of
     *   the frame. A page the user photographed fills most of it; a small quad
     *   is nearly always a text block or a piece of furniture.
     */
    fun findBest(
        lines: List<Line>,
        width: Int,
        height: Int,
        minAreaFraction: Float = 0.15f,
        maxAreaFraction: Float = 0.999f,
    ): ScoredQuadrilateral? {
        if (lines.size < 4) return null

        // Split by orientation first: a quadrilateral needs two roughly
        // opposing pairs, and pairing within an orientation is what guarantees
        // "left and right" rather than four arbitrary lines.
        val (horizontal, vertical) = lines.partition { isHorizontal(it) }
        if (horizontal.size < 2 || vertical.size < 2) return null

        var best: ScoredQuadrilateral? = null

        for (topIndex in horizontal.indices) {
            for (bottomIndex in topIndex + 1 until horizontal.size) {
                for (leftIndex in vertical.indices) {
                    for (rightIndex in leftIndex + 1 until vertical.size) {
                        val candidate =
                            buildQuadrilateral(
                                horizontal[topIndex],
                                horizontal[bottomIndex],
                                vertical[leftIndex],
                                vertical[rightIndex],
                                width,
                                height,
                            ) ?: continue

                        val area = candidate.boundary.areaFraction
                        if (area < minAreaFraction || area > maxAreaFraction) continue
                        if (best == null || candidate.score > best.score) best = candidate
                    }
                }
            }
        }
        return best
    }

    private fun isHorizontal(line: Line): Boolean {
        // theta is the normal's angle, so a horizontal edge has a vertical
        // normal: theta near 90 degrees.
        val degrees = Math.toDegrees(line.theta.toDouble())
        return degrees in 45.0..135.0
    }

    private fun buildQuadrilateral(
        top: Line,
        bottom: Line,
        left: Line,
        right: Line,
        width: Int,
        height: Int,
    ): ScoredQuadrilateral? {
        val corners =
            listOf(
                top.intersect(left) ?: return null,
                top.intersect(right) ?: return null,
                bottom.intersect(right) ?: return null,
                bottom.intersect(left) ?: return null,
            )

        // Corners well outside the frame mean the lines were never page edges.
        val margin = max(width, height) * 0.25f
        if (corners.any { (x, y) ->
                x < -margin || y < -margin || x > width + margin || y > height + margin
            }
        ) {
            return null
        }

        val ordered = orderClockwise(corners)
        val boundary =
            PageBoundary(
                topLeft = ordered[0].normalize(width, height),
                topRight = ordered[1].normalize(width, height),
                bottomRight = ordered[2].normalize(width, height),
                bottomLeft = ordered[3].normalize(width, height),
            )

        val score = score(boundary, listOf(top, bottom, left, right))
        return ScoredQuadrilateral(boundary, score)
    }

    /**
     * Sorts four points clockwise from the top-left by angle about their
     * centroid. Intersections arrive in whatever order the lines were paired,
     * and a boundary with crossed corners is a bow-tie, not a page.
     */
    internal fun orderClockwise(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val centroidX = points.sumOf { it.first.toDouble() }.toFloat() / points.size
        val centroidY = points.sumOf { it.second.toDouble() }.toFloat() / points.size
        return points
            .sortedBy { (x, y) -> atan2(y - centroidY, x - centroidX) }
            .let { sorted ->
                // atan2 sorts from -π (pointing left) counter-clockwise in
                // screen coordinates; rotate so the top-left comes first.
                val startIndex =
                    sorted.indices.minBy { index ->
                        val (x, y) = sorted[index]
                        (x - 0f) + (y - 0f)
                    }
                List(sorted.size) { sorted[(startIndex + it) % sorted.size] }
            }
    }

    /**
     * How page-like a quadrilateral is, 0..1.
     *
     * Combines four independent signals so no single one can be gamed: the
     * evidence behind the lines, how nearly rectangular the corners are, how
     * parallel opposite sides are, and how much of the frame it fills. A text
     * block scores badly on area; a desk edge scores badly on parallelism.
     */
    private fun score(
        boundary: PageBoundary,
        lines: List<Line>,
    ): Float {
        val corners = boundary.corners

        var cornerScore = 0f
        for (index in corners.indices) {
            val previous = corners[(index + corners.size - 1) % corners.size]
            val current = corners[index]
            val next = corners[(index + 1) % corners.size]
            val angle = angleAt(previous, current, next)
            // 1 at a right angle, 0 at 45 or 135 degrees.
            cornerScore += (1f - abs(angle - PI.toFloat() / 2f) / (PI.toFloat() / 4f)).coerceIn(0f, 1f)
        }
        cornerScore /= corners.size

        val topWidth = distance(corners[0], corners[1])
        val bottomWidth = distance(corners[3], corners[2])
        val leftHeight = distance(corners[0], corners[3])
        val rightHeight = distance(corners[1], corners[2])
        val widthRatio = min(topWidth, bottomWidth) / max(topWidth, bottomWidth).coerceAtLeast(1e-6f)
        val heightRatio = min(leftHeight, rightHeight) / max(leftHeight, rightHeight).coerceAtLeast(1e-6f)
        val parallelScore = (widthRatio + heightRatio) / 2f

        val areaScore = boundary.areaFraction.coerceIn(0f, 1f)

        val maxVotes = lines.maxOf { it.votes }.coerceAtLeast(1e-6f)
        val evidenceScore = lines.map { it.votes / maxVotes }.average().toFloat()

        return (cornerScore * 0.35f) + (parallelScore * 0.25f) + (areaScore * 0.25f) + (evidenceScore * 0.15f)
    }

    private fun angleAt(
        previous: NormalizedPoint,
        current: NormalizedPoint,
        next: NormalizedPoint,
    ): Float {
        val ax = previous.x - current.x
        val ay = previous.y - current.y
        val bx = next.x - current.x
        val by = next.y - current.y
        val dot = ax * bx + ay * by
        val magnitude = hypot(ax, ay) * hypot(bx, by)
        if (magnitude < 1e-9f) return 0f
        return kotlin.math.acos((dot / magnitude).coerceIn(-1f, 1f))
    }

    private fun distance(
        a: NormalizedPoint,
        b: NormalizedPoint,
    ): Float = hypot(a.x - b.x, a.y - b.y)

    private fun Pair<Float, Float>.normalize(
        width: Int,
        height: Int,
    ): NormalizedPoint =
        NormalizedPoint(
            x = (first / width).coerceIn(0f, 1f),
            y = (second / height).coerceIn(0f, 1f),
        )
}

data class ScoredQuadrilateral(
    val boundary: PageBoundary,
    val score: Float,
)
