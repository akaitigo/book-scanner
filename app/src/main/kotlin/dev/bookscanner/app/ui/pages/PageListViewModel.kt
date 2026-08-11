package dev.bookscanner.app.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bookscanner.app.ui.sessions.readableMessage
import dev.bookscanner.core.contracts.ExportPage
import dev.bookscanner.core.contracts.PageId
import dev.bookscanner.core.contracts.PdfExporter
import dev.bookscanner.core.contracts.ScanRepository
import dev.bookscanner.core.contracts.ScannedPage
import dev.bookscanner.core.contracts.SessionId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream

class PageListViewModel(
    private val sessionId: SessionId,
    private val repository: ScanRepository,
    private val exporter: PdfExporter,
) : ViewModel() {
    data class PageItem(
        val page: ScannedPage,
        val file: File,
    ) {
        val id: PageId get() = page.id
    }

    data class ExportProgress(
        val pagesDone: Int,
        val totalPages: Int,
    ) {
        val fraction: Float get() = if (totalPages == 0) 0f else pagesDone.toFloat() / totalPages
    }

    data class UiState(
        val title: String = "",
        val pages: List<PageItem> = emptyList(),
        val selection: Set<PageId> = emptySet(),
        val loading: Boolean = true,
        /**
         * A load failure that left the screen with no pages to show. Rendered
         * with a retry control; a snackbar would leave behind an empty grid
         * that reads as "this scan has no pages".
         */
        val loadError: String? = null,
        val recovered: Boolean = false,
        /**
         * Reorder is an explicit mode rather than an always-live long-press
         * gesture, because long-press already means "select" on Android.
         */
        val reordering: Boolean = false,
        val exportProgress: ExportProgress? = null,
        val exportedSuccessfully: Boolean = false,
        val errorMessage: String? = null,
    ) {
        val selectionMode: Boolean get() = selection.isNotEmpty()
        val exporting: Boolean get() = exportProgress != null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var exportJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { repository.getSession(sessionId) }
                .onSuccess { session ->
                    if (session == null) {
                        _state.update {
                            it.copy(loading = false, loadError = "This scan no longer exists.")
                        }
                        return@onSuccess
                    }
                    val items = session.pages.map { PageItem(it, repository.pageFile(sessionId, it)) }
                    _state.update { current ->
                        current.copy(
                            title = session.title,
                            pages = items,
                            // Drop selections for pages that no longer exist.
                            selection = current.selection intersect items.map { it.id }.toSet(),
                            loading = false,
                            loadError = null,
                            recovered = session.recovered,
                        )
                    }
                }.onFailure { error ->
                    _state.update {
                        it.copy(loading = false, loadError = "Could not open this scan: ${error.readableMessage()}")
                    }
                }
        }
    }

    // ---- modes ----

    fun startReordering() {
        _state.update { it.copy(reordering = true, selection = emptySet()) }
    }

    fun stopReordering() {
        _state.update { it.copy(reordering = false) }
    }

    // ---- selection ----

    fun toggleSelection(id: PageId) {
        _state.update { current ->
            current.copy(
                selection = if (id in current.selection) current.selection - id else current.selection + id,
            )
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    fun selectAll() {
        _state.update { current -> current.copy(selection = current.pages.map { it.id }.toSet()) }
    }

    fun deleteSelected() {
        val selection = _state.value.selection
        if (selection.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.removePages(sessionId, selection) }
                .onSuccess { _state.update { it.copy(selection = emptySet()) } }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.readableMessage()) } }
            refresh()
        }
    }

    // ---- ordering ----

    /**
     * Moves a page one position and saves immediately. This is the button-based
     * path, which exists so reordering never *requires* a drag gesture.
     */
    fun nudgePage(
        index: Int,
        offset: Int,
    ) {
        val target = index + offset
        if (target !in _state.value.pages.indices) return
        movePage(index, target)
        commitOrder()
    }

    /**
     * Moves the page at [from] to [to], as a drag gesture does.
     *
     * The reordered list is published immediately so the grid follows the
     * finger without waiting on storage; [commitOrder] persists it on drop, so
     * a drag across twenty pages is one write rather than twenty.
     */
    fun movePage(
        from: Int,
        to: Int,
    ) {
        val pages = _state.value.pages
        if (from !in pages.indices || to !in pages.indices || from == to) return
        val reordered = pages.toMutableList().apply { add(to, removeAt(from)) }
        _state.update { it.copy(pages = reordered) }
    }

    /** Persists the order shown in the UI. Called when a drag gesture ends. */
    fun commitOrder() {
        val ordered = _state.value.pages.map { it.id }
        if (ordered.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.reorderPages(sessionId, ordered) }
                .onFailure { error ->
                    _state.update { it.copy(errorMessage = error.readableMessage()) }
                    refresh()
                }
        }
    }

    // ---- export ----

    fun export(openOutput: () -> OutputStream) {
        val pages = _state.value.pages
        if (pages.isEmpty()) {
            _state.update { it.copy(errorMessage = "Add at least one page before exporting") }
            return
        }
        if (_state.value.exporting) return

        exportJob =
            viewModelScope.launch {
                _state.update { it.copy(exportProgress = ExportProgress(0, pages.size), exportedSuccessfully = false) }
                try {
                    openOutput().use { output ->
                        exporter.export(
                            pages = pages.map { ExportPage(it.file, it.page.geometry) },
                            output = output,
                        ) { done, total ->
                            _state.update { it.copy(exportProgress = ExportProgress(done, total)) }
                        }
                    }
                    _state.update { it.copy(exportProgress = null, exportedSuccessfully = true) }
                } catch (cancellation: CancellationException) {
                    _state.update { it.copy(exportProgress = null) }
                    throw cancellation
                } catch (error: Throwable) {
                    _state.update {
                        it.copy(exportProgress = null, errorMessage = "Export failed: ${error.readableMessage()}")
                    }
                }
            }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _state.update { it.copy(exportProgress = null) }
    }

    fun consumeExportSuccess() {
        _state.update { it.copy(exportedSuccessfully = false) }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }
}
