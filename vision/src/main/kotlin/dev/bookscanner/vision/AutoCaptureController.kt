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
        /**
         * Mean absolute difference between neighbouring pixels, below which the
         * frame is judged too featureless to be worth capturing.
         *
         * A **sharpness floor, not a page test**: detection is too unreliable to
         * gate the shutter on, but "is there readable detail in view" is
         * answerable from the pixels alone. It exists because auto-capture
         * photographed a blurred floor and a cable while the phone was being
         * lowered — still, briefly, and pointed at nothing.
         *
         * Measured at preview resolution on real captures: the blurred floor
         * scored 1.10, ten real page photographs ranged 1.75 to 3.43. The
         * threshold sits nearer the failure than the worst success, so a missed
         * capture (press the button) is likelier than a junk one.
         */
        val minimumDetail: Float = 1.3f,
    )

    /** What the UI should tell the user right now. */
    enum class Status {
        /** Nothing steady yet — the user is moving, or has just captured. */
        SEARCHING,

        /** Something is being held still; a capture is imminent. */
        HOLDING,

        /** Waiting for the scene to change before considering another capture. */
        WAITING_FOR_NEW_PAGE,

        /** Steady, but nothing worth photographing is in view. */
        NO_DETAIL,
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

        // Checked before stillness, so that lowering the phone — which is both
        // blurred and briefly steady — cannot reach the trigger.
        if (meanNeighbourDifference(frame) < settings.minimumDetail) {
            stillSinceMillis = null
            return Decision(Status.NO_DETAIL, shouldCapture = false, boundary = boundary)
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

    /**
     * Mean absolute difference between horizontally neighbouring pixels.
     *
     * A page carries text, and text is high-frequency detail; a blurred or
     * empty scene has almost none. Sampling every [SAMPLE_STRIDE]th pixel keeps
     * this affordable on the camera thread, and the last pixel of each row is
     * skipped so the wrap to the next row is not counted as an edge.
     */
    private fun meanNeighbourDifference(frame: GrayscaleImage): Float {
        val pixels = frame.pixels
        var total = 0L
        var count = 0
        var index = 0
        while (index < pixels.size - 1) {
            if ((index + 1) % frame.width != 0) {
                total += abs((pixels[index].toInt() and 0xFF) - (pixels[index + 1].toInt() and 0xFF))
                count++
            }
            index += SAMPLE_STRIDE
        }
        return if (count == 0) 0f else total.toFloat() / count
    }

    private companion object {
        const val SAMPLE_STRIDE = 7
    }
}
