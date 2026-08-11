package dev.bookscanner.core.contracts

/**
 * Non-destructive edit state of a page. The original image file is never
 * modified; geometry is applied at preview/export time by a [PageTransformer].
 *
 * Coordinate contract: [crop] is expressed in the coordinate system of the
 * image AFTER [rotationDegrees] has been applied — i.e. in the orientation
 * the user was looking at when they dragged the crop handles.
 */
data class PageGeometry(
    val rotationDegrees: Int = 0,
    val crop: CropRect? = null,
    /**
     * Four corners of the page within the image, for perspective correction.
     *
     * A photographed page is a perspective projection: its edges are not
     * parallel, so an axis-aligned [crop] cannot straighten it. When present
     * this is applied first — the quadrilateral is warped to a rectangle — and
     * [crop] then applies to the corrected result.
     *
     * Like [crop], coordinates are expressed after [rotationDegrees].
     */
    val boundary: PageBoundary? = null,
) {
    init {
        require(rotationDegrees in VALID_ROTATIONS) {
            "rotationDegrees must be one of $VALID_ROTATIONS, got $rotationDegrees"
        }
    }

    /** Rotate clockwise by [degrees] (any multiple of 90, may be negative). */
    fun rotatedBy(degrees: Int): PageGeometry {
        require(degrees % 90 == 0) { "Rotation must be a multiple of 90, got $degrees" }
        return copy(rotationDegrees = Math.floorMod(rotationDegrees + degrees, 360))
    }

    val isIdentity: Boolean
        get() = rotationDegrees == 0 && crop == null && boundary == null

    /** True when producing this page requires touching pixels. */
    val requiresRedraw: Boolean
        get() = crop != null || boundary != null

    companion object {
        val VALID_ROTATIONS = setOf(0, 90, 180, 270)

        // Declared after VALID_ROTATIONS: constructing a PageGeometry runs the
        // init block, which reads that set. Reversing the order leaves it null
        // during companion initialization.
        val IDENTITY = PageGeometry()
    }
}

/**
 * Axis-aligned crop rectangle in normalized coordinates (fractions of the
 * rotated image's width/height), so it is resolution-independent.
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "Crop coordinates must be within [0, 1]: $this"
        }
        require(left < right && top < bottom) {
            "Crop must have positive area (left < right, top < bottom): $this"
        }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)

        /**
         * Build a valid crop from possibly out-of-range/inverted drag input,
         * clamping to [0, 1] and enforcing a minimum size of [minSize].
         * Returns null if the input cannot yield a positive-area rect.
         */
        fun sanitized(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            minSize: Float = 0.01f,
        ): CropRect? {
            val l = minOf(left, right).coerceIn(0f, 1f)
            val r = maxOf(left, right).coerceIn(0f, 1f)
            val t = minOf(top, bottom).coerceIn(0f, 1f)
            val b = maxOf(top, bottom).coerceIn(0f, 1f)
            if (r - l < minSize || b - t < minSize) return null
            return CropRect(l, t, r, b)
        }
    }
}
