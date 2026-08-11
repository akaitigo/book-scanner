package dev.bookscanner.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bookscanner.app.ui.sessions.readableMessage
import dev.bookscanner.core.contracts.CropRect
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
) : ViewModel() {
    data class UiState(
        val imageFile: File? = null,
        val savedGeometry: PageGeometry = PageGeometry.IDENTITY,
        val geometry: PageGeometry = PageGeometry.IDENTITY,
        val loading: Boolean = true,
        val saving: Boolean = false,
        val saved: Boolean = false,
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
                        )
                    }
                }.onFailure { error ->
                    _state.update { it.copy(loading = false, errorMessage = error.readableMessage()) }
                }
        }
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
}
