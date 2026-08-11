package dev.bookscanner.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bookscanner.app.ui.sessions.readableMessage
import dev.bookscanner.core.contracts.GrayscaleImage
import dev.bookscanner.core.contracts.PageBoundary
import dev.bookscanner.core.contracts.PageDetector
import dev.bookscanner.core.contracts.ScanRepository
import dev.bookscanner.core.contracts.ScannedPage
import dev.bookscanner.core.contracts.SessionId
import dev.bookscanner.core.session.PageIngestor
import dev.bookscanner.vision.AutoCaptureController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

/**
 * Drives the capture loop. Deliberately knows nothing about CameraX: it
 * receives already-captured bytes, which keeps the whole loop — ordering,
 * error handling, in-flight state — testable on the JVM.
 */
class CaptureViewModel(
    private val sessionId: SessionId,
    private val repository: ScanRepository,
    private val ingestor: PageIngestor,
    /** Optional: without it, auto-capture still works on stillness alone. */
    private val detector: PageDetector? = null,
    private val autoCapture: AutoCaptureController = AutoCaptureController(),
) : ViewModel() {
    data class UiState(
        val pageCount: Int = 0,
        /** Most recent pages, newest first, for the confirmation strip. */
        val recentPages: List<PageThumbnail> = emptyList(),
        val capturing: Boolean = false,
        val importing: Boolean = false,
        val autoCapture: Boolean = false,
        val autoStatus: AutoCaptureController.Status = AutoCaptureController.Status.SEARCHING,
        /** 0..1 through the hold, for a progress ring around the shutter. */
        val holdProgress: Float = 0f,
        /** Most recent detection, drawn over the preview. Advisory only. */
        val previewBoundary: PageBoundary? = null,
        val errorMessage: String? = null,
    ) {
        val busy: Boolean get() = capturing || importing
    }

    data class PageThumbnail(
        val page: ScannedPage,
        val file: File,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var framesSeen = 0L
    private var latestBoundary: PageBoundary? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repository.getSession(sessionId) }
                .onSuccess { session -> session?.let { publish(it.pages) } }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.readableMessage()) } }
        }
    }

    /**
     * Appends a captured frame. [capturing] gates the shutter so a rapid
     * double-tap cannot interleave two staged pages and reorder the book.
     */
    fun onFrameCaptured(bytes: ByteArray) {
        // The guard is applied synchronously, before launching: setting it
        // inside the coroutine lets two rapid shutter presses both pass the
        // check and interleave their staged pages.
        if (!claimBusy { it.copy(capturing = true) }) return
        viewModelScope.launch {
            runCatching { ingestor.ingest(sessionId, bytes) }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.readableMessage()) } }
            _state.update { it.copy(capturing = false) }
            refresh()
        }
    }

    fun onCaptureFailed(error: Throwable) {
        _state.update { it.copy(capturing = false, errorMessage = error.readableMessage()) }
    }

    /**
     * Imports picked images in the order the picker returned them.
     *
     * Sequential by design: [PageIngestor] appends, so concurrent imports
     * would produce a nondeterministic page order. One failure does not abort
     * the rest — a single unsupported file should not discard a 40-image
     * import.
     */
    fun onImagesPicked(sources: List<() -> InputStream>) {
        if (sources.isEmpty()) return
        if (!claimBusy { it.copy(importing = true) }) return
        viewModelScope.launch {
            var failures = 0
            sources.forEach { open ->
                runCatching { open().use { stream -> ingestor.ingest(sessionId, stream) } }
                    .onFailure { failures++ }
            }
            _state.update {
                it.copy(
                    importing = false,
                    errorMessage = if (failures > 0) importFailureMessage(failures, sources.size) else it.errorMessage,
                )
            }
            refresh()
        }
    }

    fun deletePage(page: ScannedPage) {
        viewModelScope.launch {
            runCatching { repository.removePages(sessionId, setOf(page.id)) }
                .onSuccess { publish(it.pages) }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.readableMessage()) } }
        }
    }

    /**
     * Turns automatic capture on or off.
     *
     * Off by default: it fires the shutter on its own, and a feature that acts
     * without being asked should be asked for first.
     */
    fun setAutoCapture(enabled: Boolean) {
        autoCapture.reset()
        _state.update {
            it.copy(
                autoCapture = enabled,
                autoStatus = AutoCaptureController.Status.SEARCHING,
                holdProgress = 0f,
                previewBoundary = null,
            )
        }
    }

    /**
     * Feeds a preview frame to the auto-capture logic.
     *
     * @return true when the caller should take a photograph now.
     *
     * Detection runs on only every [DETECT_EVERY_N_FRAMES]th frame: the shutter
     * decision does not depend on it (see [AutoCaptureController]), so paying
     * for it on every frame would buy nothing but heat.
     */
    fun onPreviewFrame(
        frame: GrayscaleImage,
        nowMillis: Long,
    ): Boolean {
        if (!_state.value.autoCapture || _state.value.busy) return false

        framesSeen++
        if (framesSeen % DETECT_EVERY_N_FRAMES == 0L) {
            latestBoundary = runCatching { detector?.detect(frame)?.boundary }.getOrNull()
        }

        val decision = autoCapture.onFrame(frame, nowMillis, latestBoundary)
        _state.update {
            it.copy(
                autoStatus = decision.status,
                holdProgress = decision.holdProgress,
                previewBoundary = decision.boundary,
            )
        }
        return decision.shouldCapture
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * Atomically takes the busy slot. Returns false when another capture or
     * import already holds it.
     */
    private fun claimBusy(mark: (UiState) -> UiState): Boolean {
        var claimed = false
        _state.update { current ->
            if (current.busy) {
                current
            } else {
                claimed = true
                mark(current)
            }
        }
        return claimed
    }

    private fun publish(pages: List<ScannedPage>) {
        _state.update { current ->
            current.copy(
                pageCount = pages.size,
                recentPages =
                    pages
                        .asReversed()
                        .take(RECENT_PAGE_COUNT)
                        .map { PageThumbnail(it, repository.pageFile(sessionId, it)) },
            )
        }
    }

    private fun importFailureMessage(
        failures: Int,
        total: Int,
    ): String =
        if (failures == total) {
            "Could not import any of the $total selected images"
        } else {
            "Imported ${total - failures} of $total images; $failures could not be read"
        }

    private companion object {
        const val RECENT_PAGE_COUNT = 12

        /** Detection is advisory, so it does not need every frame. */
        const val DETECT_EVERY_N_FRAMES = 4L
    }
}
