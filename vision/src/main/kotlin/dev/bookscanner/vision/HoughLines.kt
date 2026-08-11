package dev.bookscanner.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A line in normal form: `x·cos(theta) + y·sin(theta) = rho`.
 *
 * Normal form rather than slope-intercept because a page's left and right
 * edges are near-vertical, where slope goes to infinity. Every line is
 * representable here, which is the whole reason the Hough transform uses it.
 */
data class Line(
    val rho: Float,
    val theta: Float,
    /** Accumulator weight; higher means more edge pixels agreed on this line. */
    val votes: Float,
) {
    /** Intersection with [other], or null when the two are near-parallel. */
    fun intersect(other: Line): Pair<Float, Float>? {
        val sinA = sin(theta)
        val cosA = cos(theta)
        val sinB = sin(other.theta)
        val cosB = cos(other.theta)
        val determinant = cosA * sinB - sinA * cosB
        // Near-parallel lines meet at absurd distances; treating them as a
        // corner is how quadrilateral fitting produces nonsense.
        if (abs(determinant) < 1e-6f) return null
        val x = (rho * sinB - other.rho * sinA) / determinant
        val y = (other.rho * cosA - rho * cosB) / determinant
        return x to y
    }

    /** Smallest angle between this line and [other], 0..π/2. */
    fun angleTo(other: Line): Float {
        val diff = abs(theta - other.theta) % PI.toFloat()
        return minOf(diff, PI.toFloat() - diff)
    }
}

/**
 * Hough line detection over a thresholded gradient field.
 *
 * Chosen over contour tracing because a book page's edge is frequently
 * *broken* — a finger, a shadow, or a low-contrast corner interrupts it. A
 * contour needs the outline to close; Hough only needs enough collinear
 * evidence, so it recovers the edge from the parts that survived.
 */
object HoughLines {
    /**
     * @param thetaSteps angular resolution of the accumulator. 180 gives 1°,
     *   which is finer than the corner accuracy the pipeline needs and keeps
     *   the accumulator small.
     */
    fun detect(
        points: List<EdgePoint>,
        width: Int,
        height: Int,
        thetaSteps: Int = 180,
        maxLines: Int = 24,
        minVoteFraction: Float = 0.25f,
    ): List<Line> {
        if (points.isEmpty()) return emptyList()

        val maxRho = hypot(width.toFloat(), height.toFloat())
        val rhoSteps = (maxRho * 2).roundToInt() + 1
        val accumulator = FloatArray(thetaSteps * rhoSteps)

        val sinTable = FloatArray(thetaSteps)
        val cosTable = FloatArray(thetaSteps)
        for (t in 0 until thetaSteps) {
            val theta = PI.toFloat() * t / thetaSteps
            sinTable[t] = sin(theta)
            cosTable[t] = cos(theta)
        }

        for (point in points) {
            for (t in 0 until thetaSteps) {
                val rho = point.x * cosTable[t] + point.y * sinTable[t]
                val rhoIndex = (rho + maxRho).roundToInt()
                if (rhoIndex in 0 until rhoSteps) {
                    // Weighted by gradient strength: a crisp page edge should
                    // outvote a faint texture line of the same length.
                    accumulator[t * rhoSteps + rhoIndex] += point.magnitude
                }
            }
        }

        val peak = accumulator.maxOrNull() ?: return emptyList()
        if (peak <= 0f) return emptyList()
        val minVotes = peak * minVoteFraction

        val candidates = ArrayList<Line>()
        for (t in 0 until thetaSteps) {
            for (r in 0 until rhoSteps) {
                val votes = accumulator[t * rhoSteps + r]
                if (votes < minVotes) continue
                if (!isLocalMaximum(accumulator, t, r, thetaSteps, rhoSteps)) continue
                candidates +=
                    Line(
                        rho = r - maxRho,
                        theta = PI.toFloat() * t / thetaSteps,
                        votes = votes,
                    )
            }
        }

        return candidates
            .sortedByDescending { it.votes }
            .let { suppressNeighbours(it, maxRho) }
            .take(maxLines)
    }

    /**
     * Keeps a cell only if no neighbour is stronger, so one thick edge yields
     * one line instead of a cluster of near-identical ones.
     */
    private fun isLocalMaximum(
        accumulator: FloatArray,
        t: Int,
        r: Int,
        thetaSteps: Int,
        rhoSteps: Int,
    ): Boolean {
        val value = accumulator[t * rhoSteps + r]
        for (dt in -1..1) {
            for (dr in -1..1) {
                if (dt == 0 && dr == 0) continue
                // Theta wraps: 179° and 0° are adjacent directions.
                val nt = ((t + dt) % thetaSteps + thetaSteps) % thetaSteps
                val nr = r + dr
                if (nr !in 0 until rhoSteps) continue
                if (accumulator[nt * rhoSteps + nr] > value) return false
            }
        }
        return true
    }

    /**
     * Drops lines that are nearly the same as a stronger one already kept.
     * Local maxima alone still leave duplicates a few cells apart when an edge
     * is slightly curved.
     */
    private fun suppressNeighbours(
        lines: List<Line>,
        maxRho: Float,
    ): List<Line> {
        val rhoTolerance = maxRho * 0.03f
        val thetaTolerance = (PI / 18).toFloat() // 10 degrees
        val kept = ArrayList<Line>()
        for (line in lines) {
            val duplicate =
                kept.any { existing ->
                    val thetaClose = line.angleTo(existing) < thetaTolerance
                    // Opposite-facing normals describe the same line with
                    // negated rho, so compare both signs.
                    val rhoClose =
                        abs(line.rho - existing.rho) < rhoTolerance ||
                            abs(line.rho + existing.rho) < rhoTolerance
                    thetaClose && rhoClose
                }
            if (!duplicate) kept += line
        }
        return kept
    }
}
