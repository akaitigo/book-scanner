package dev.bookscanner.vision

import dev.bookscanner.core.contracts.GrayscaleImage
import dev.bookscanner.core.contracts.PageBoundary
import kotlin.math.abs

/**
 * Decides when to take a photograph without the user pressing anything.
 *
 * The trigger is **stillness**, not detection. Detection on real photographs
 * finds a usable boundary in about two frames out of five
 * (`docs/benchmark.md`), so gating the shutter on it would mean a dark cover or
 * an awkwardly framed page could never be captured at all. A page that is held
 * steady, on the other hand, is unambiguous and cheap to recognise.
 *
 * The sequence a person actually performs — turn the page, settle, hold — maps
 * onto: something changed, then nothing is changing, then shoot once.
 *
 * Pure JVM so the whole state machine is testable; the camera only supplies
 * frames.
 */
class AutoCaptureController(
    private val settings: Settings = Settings(),
) {
    data class Settings(
        /**
         * Mean per-pixel luminance change below which a frame counts as still.
         * Sensor noise alone moves a static scene by a couple of levels.
         */
        val stillnessThreshold: Float = 2.5f,
        /**
         * How long the frame must stay still before firing. Long enough that
         * the hand has actually settled, short enough not to feel broken.
         */
        val requiredStillMillis: Long = 700,
        /**
         * How different a frame must be from the last captured one to count as
         * a new page. Without this the camera would fire repeatedly at a page
         * that is simply sitting there.
         */
        val newSceneThreshold: Float = 8f,
        /** Ignore everything for this long after firing, while the shutter runs. */
        val cooldownMillis: Long = 1_200,
        /**
         * A detected page shortens the wait: the scene is more likely to be
         * what we want, so the confirmation can be quicker. It never *extends*
         * it, because detection failing must not block capture.
         */
        val detectedStillMillis: Long = 450,
    )

    /** What the UI should tell the user right now. */
    enum class Status {
        /** Nothing steady yet — the user is moving, or has just captured. */
        SEARCHING,

        /** Something is being held still; a capture is imminent. */
        HOLDING,

        /** Waiting for the scene to change before considering another capture. */
        WAITING_FOR_NEW_PAGE,
    }

    data class Decision(
        val status: Status,
        val shouldCapture: Boolean,
        /** Drawn as an outline when present; purely advisory. */
        val boundary: PageBoundary? = null,
        /** 0..1, how far through the hold we are. Drives a progress ring. */
        val holdProgress: Float = 0f,
    )

    private var previousFrame: ByteArray? = null
    private var lastCapturedFrame: ByteArray? = null
    private var stillSinceMillis: Long? = null
    private var cooldownUntilMillis: Long = 0
    private var awaitingNewScene = false

    /**
     * @param boundary the most recent detection, or null. Advisory only.
     * @return whether to fire the shutter, and what to show meanwhile.
     */
    fun onFrame(
        frame: GrayscaleImage,
        nowMillis: Long,
        boundary: PageBoundary? = null,
    ): Decision {
        val previous = previousFrame
        previousFrame = frame.pixels.copyOf()

        if (nowMillis < cooldownUntilMillis) {
            stillSinceMillis = null
            return Decision(Status.SEARCHING, shouldCapture = false, boundary = boundary)
        }

        if (previous == null || previous.size != frame.pixels.size) {
            stillSinceMillis = null
            return Decision(Status.SEARCHING, shouldCapture = false, boundary = boundary)
        }

        val motion = meanAbsoluteDifference(previous, frame.pixels)
        if (motion > settings.stillnessThreshold) {
            stillSinceMillis = null
            // Movement is also how we notice the page was turned.
            if (awaitingNewScene && sceneChangedSinceCapture(frame)) awaitingNewScene = false
            val status = if (awaitingNewScene) Status.WAITING_FOR_NEW_PAGE else Status.SEARCHING
            return Decision(status, shouldCapture = false, boundary = boundary)
        }

        if (awaitingNewScene) {
            if (!sceneChangedSinceCapture(frame)) {
                return Decision(Status.WAITING_FOR_NEW_PAGE, shouldCapture = false, boundary = boundary)
            }
            awaitingNewScene = false
        }

        val stillSince = stillSinceMillis ?: nowMillis.also { stillSinceMillis = it }
        val required = if (boundary != null) settings.detectedStillMillis else settings.requiredStillMillis
        val held = nowMillis - stillSince

        if (held < required) {
            return Decision(
                status = Status.HOLDING,
                shouldCapture = false,
                boundary = boundary,
                holdProgress = (held.toFloat() / required).coerceIn(0f, 1f),
            )
        }

        lastCapturedFrame = frame.pixels.copyOf()
        stillSinceMillis = null
        cooldownUntilMillis = nowMillis + settings.cooldownMillis
        awaitingNewScene = true
        return Decision(Status.SEARCHING, shouldCapture = true, boundary = boundary, holdProgress = 1f)
    }

    /** Forgets everything — call when auto-capture is switched off or paused. */
    fun reset() {
        previousFrame = null
        lastCapturedFrame = null
        stillSinceMillis = null
        cooldownUntilMillis = 0
        awaitingNewScene = false
    }

    private fun sceneChangedSinceCapture(frame: GrayscaleImage): Boolean {
        val last = lastCapturedFrame ?: return true
        if (last.size != frame.pixels.size) return true
        return meanAbsoluteDifference(last, frame.pixels) > settings.newSceneThreshold
    }

    /**
     * Mean absolute difference over a subsample.
     *
     * Every 7th pixel rather than every pixel: this runs on the camera thread
     * for every frame, and a stride that is coprime with the row width avoids
     * sampling the same column repeatedly.
     */
    private fun meanAbsoluteDifference(
        a: ByteArray,
        b: ByteArray,
    ): Float {
        var total = 0L
        var count = 0
        var index = 0
        while (index < a.size) {
            total += abs((a[index].toInt() and 0xFF) - (b[index].toInt() and 0xFF))
            count++
            index += SAMPLE_STRIDE
        }
        return if (count == 0) 0f else total.toFloat() / count
    }

    private companion object {
        const val SAMPLE_STRIDE = 7
    }
}
