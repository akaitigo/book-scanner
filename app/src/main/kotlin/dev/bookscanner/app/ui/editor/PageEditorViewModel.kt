package dev.bookscanner.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bookscanner.app.ui.sessions.readableMessage
import dev.bookscanner.core.contracts.CropRect
import dev.bookscanner.core.contracts.NormalizedPoint
import dev.bookscanner.core.contracts.PageBoundary
import dev.bookscanner.core.contracts.PageDetection
import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.core.contracts.PageId
import dev.bookscanner.core.contracts.ScanRepository
import dev.bookscanner.core.contracts.SessionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual crop and rotate. Edits are geometry only — the captured file is never
 * touched — so cancelling, crashing, or getting it wrong cannot destroy a page.
 */
class PageEditorViewModel(
    private val sessionId: SessionId,
    private val pageId: PageId,
    private val repository: ScanRepository,
    /**
     * Runs page detection. Nullable so the editor works without it — detection
     * is an assist, and the manual path must never depend on it.
     */
    private val detectPage: (suspend (File, PageGeometry) -> PageDetection)? = null,
) : ViewModel() {
    data class UiState(
        val imageFile: File? = null,
        val savedGeometry: PageGeometry = PageGeometry.IDENTITY,
        val geometry: PageGeometry = PageGeometry.IDENTITY,
        val loading: Boolean = true,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val detecting: Boolean = false,
        /** Set when detection ran and found nothing, so the UI can say so. */
        val detectionFailed: Boolean = false,
        val detectionAvailable: Boolean = false,
        val errorMessage: String? = null,
    ) {
        val dirty: Boolean get() = geometry != savedGeometry

        /** Crop handles start from the full page when nothing is cropped yet. */
        val editableCrop: CropRect get() = geometry.crop ?: CropRect.FULL
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            runCatching { repository.getSession(sessionId) }
                .onSuccess { session ->
                    val page = session?.page(pageId)
                    if (page == null) {
                        _state.update { it.copy(loading = false, errorMessage = "This page no longer exists") }
                        return@onSuccess
                    }
                    _state.update {
                        it.copy(
                            imageFile = repository.pageFile(sessionId, page),
                            savedGeometry = page.geometry,
                            geometry = page.geometry,
                            loading = false,
                            detectionAvailable = detectPage != null,
                        )
                    }
                }.onFailure { error ->
                    _state.update { it.copy(loading = false, errorMessage = error.readableMessage()) }
                }
        }
    }

    /**
     * Detects the page and applies the result as a perspective boundary.
     *
     * Nothing is written to storage: this is a proposal the user can Save,
     * change, or discard, which is what keeps a wrong detection harmless.
     */
    fun autoDetect() {
        val detect = detectPage ?: return
        val file = _state.value.imageFile ?: return
        if (_state.value.detecting) return

        viewModelScope.launch {
            _state.update { it.copy(detecting = true, detectionFailed = false) }
            runCatching { detect(file, _state.value.geometry) }
                .onSuccess { detection ->
                    _state.update { current ->
                        if (detection.boundary == null) {
                            current.copy(detecting = false, detectionFailed = true)
                        } else {
                            // Replaces any crop: the boundary supersedes it, and
                            // leaving both would crop the corrected page by
                            // coordinates measured against the uncorrected one.
                            current.copy(
                                detecting = false,
                                geometry = current.geometry.copy(boundary = detection.boundary, crop = null),
                            )
                        }
                    }
                }.onFailure { error ->
                    _state.update {
                        it.copy(detecting = false, errorMessage = error.readableMessage())
                    }
                }
        }
    }

    /**
     * Drags one corner of the detected boundary.
     *
     * A detection the user cannot see or adjust is one they have to trust
     * blindly; this is what makes it a proposal rather than a verdict.
     * Corner order matches [PageBoundary.corners]: top-left, top-right,
     * bottom-right, bottom-left.
     */
    fun moveBoundaryCorner(
        cornerIndex: Int,
        dx: Float,
        dy: Float,
    ) {
        _state.update { current ->
            val boundary = current.geometry.boundary ?: return@update current
            val corners = boundary.corners.toMutableList()
            if (cornerIndex !in corners.indices) return@update current

            val moved = corners[cornerIndex]
            corners[cornerIndex] =
                NormalizedPoint(
                    x = (moved.x + dx).coerceIn(0f, 1f),
                    y = (moved.y + dy).coerceIn(0f, 1f),
                )
            val updated = PageBoundary(corners[0], corners[1], corners[2], corners[3])
            // A quadrilateral that has collapsed or folded into a bow-tie
            // would warp to an unusable page; keep the last good one instead.
            if (!updated.isConvex || updated.areaFraction < MIN_BOUNDARY_AREA) return@update current
            current.copy(geometry = current.geometry.copy(boundary = updated))
        }
    }

    fun clearBoundary() {
        _state.update { it.copy(geometry = it.geometry.copy(boundary = null)) }
    }

    fun consumeDetectionFailed() {
        _state.update { it.copy(detectionFailed = false) }
    }

    fun rotateClockwise() {
        _state.update { it.copy(geometry = it.geometry.rotatedBy(90)) }
    }

    fun rotateCounterClockwise() {
        _state.update { it.copy(geometry = it.geometry.rotatedBy(-90)) }
    }

    /**
     * Applies a crop from drag input. Coordinates arrive raw from the gesture
     * — possibly inverted or out of bounds — and are sanitized here rather
     * than trusted; a degenerate rect is ignored, leaving the previous crop.
     */
    fun updateCrop(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val sanitized = CropRect.sanitized(left, top, right, bottom) ?: return
        _state.update { current ->
            current.copy(
                geometry =
                    current.geometry.copy(
                        crop = sanitized.takeIf { it != CropRect.FULL },
                    ),
            )
        }
    }

    fun resetCrop() {
        _state.update { it.copy(geometry = it.geometry.copy(crop = null)) }
    }

    /** Discards unsaved edits, returning to what is on disk. */
    fun revert() {
        _state.update { it.copy(geometry = it.savedGeometry) }
    }

    fun save() {
        val geometry = _state.value.geometry
        if (_state.value.saving) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            runCatching { repository.updatePageGeometry(sessionId, pageId, geometry) }
                .onSuccess {
                    _state.update { it.copy(saving = false, saved = true, savedGeometry = geometry) }
                }.onFailure { error ->
                    _state.update { it.copy(saving = false, errorMessage = error.readableMessage()) }
                }
        }
    }

    fun consumeSaved() {
        _state.update { it.copy(saved = false) }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private companion object {
        /** Smaller than this and the corrected page would be unusable. */
        const val MIN_BOUNDARY_AREA = 0.02f
    }
}
