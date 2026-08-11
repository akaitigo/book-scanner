package dev.bookscanner.app.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bookscanner.core.contracts.ScanRepository
import dev.bookscanner.core.contracts.ScanSession
import dev.bookscanner.core.contracts.SessionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionListViewModel(
    private val repository: ScanRepository,
) : ViewModel() {
    data class UiState(
        val sessions: List<ScanSession> = emptyList(),
        val loading: Boolean = true,
        /**
         * A load failure that left the screen with nothing to show. Rendered as
         * a state with a retry button — a snackbar would flash past and leave
         * an empty screen that is indistinguishable from "you have no scans".
         */
        val loadError: String? = null,
        /** A failed action on a screen that still has content. */
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-reads sessions from storage. Called on every return to this screen,
     * because pages may have been added or removed elsewhere and the list
     * shows page counts.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { repository.listSessions() }
                .onSuccess { sessions ->
                    _state.update { it.copy(sessions = sessions, loading = false, loadError = null) }
                }.onFailure { error ->
                    _state.update {
                        it.copy(loading = false, loadError = "Could not read your scans: ${error.readableMessage()}")
                    }
                }
        }
    }

    fun createSession(
        title: String,
        onCreated: (SessionId) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { repository.createSession(title.ifBlank { DEFAULT_TITLE }) }
                .onSuccess { session ->
                    refresh()
                    onCreated(session.id)
                }.onFailure { error ->
                    _state.update { it.copy(errorMessage = error.readableMessage()) }
                }
        }
    }

    fun deleteSession(id: SessionId) {
        viewModelScope.launch {
            runCatching { repository.deleteSession(id) }
                .onSuccess { refresh() }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.readableMessage()) } }
        }
    }

    fun renameSession(
        id: SessionId,
        title: String,
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            runCatching { repository.renameSession(id, title) }
                .onSuccess { refresh() }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.readableMessage()) } }
        }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    companion object {
        const val DEFAULT_TITLE = "Untitled scan"
    }
}

/** Exceptions reach the UI as text; a null message would render as "null". */
internal fun Throwable.readableMessage(): String = message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
