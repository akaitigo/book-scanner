package dev.bookscanner.app.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bookscanner.app.ui.components.EmptyState
import dev.bookscanner.app.ui.components.ErrorState
import dev.bookscanner.app.ui.components.LoadingState
import dev.bookscanner.core.contracts.ScanSession
import dev.bookscanner.core.contracts.SessionId
import java.text.DateFormat
import java.util.Date

object SessionListTags {
    const val LIST = "session-list"
    const val CREATE = "session-create"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    viewModel: SessionListViewModel,
    onOpenSession: (SessionId) -> Unit,
    onStartCapture: (SessionId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ScanSession?>(null) }
    var deleting by remember { mutableStateOf<ScanSession?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    state.errorMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Scans") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.loadError == null) {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New scan") },
                    modifier = Modifier.testTag(SessionListTags.CREATE),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loadError != null -> {
                    ErrorState(
                        message = requireNotNull(state.loadError),
                        onRetry = viewModel::refresh,
                    )
                }

                state.loading && state.sessions.isEmpty() -> {
                    LoadingState("Loading your scans…")
                }

                state.sessions.isEmpty() -> {
                    EmptyState(
                        title = "No scans yet",
                        body = "Start a scan to capture a book one page at a time, then export it as a PDF.",
                        actionLabel = "Start a scan",
                        onAction = { creating = true },
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag(SessionListTags.LIST),
                        // Bottom room for the FAB, which would otherwise cover
                        // the last row's actions.
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.sessions, key = { it.id.value }) { session ->
                            SessionRow(
                                session = session,
                                onOpen = { onOpenSession(session.id) },
                                onCapture = { onStartCapture(session.id) },
                                onRename = { renaming = session },
                                onDelete = { deleting = session },
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        TextPromptDialog(
            title = "New scan",
            label = "Book title",
            supportingText = "You can rename this later.",
            initialValue = "",
            confirmLabel = "Create scan",
            onDismiss = { creating = false },
            onConfirm = { title ->
                creating = false
                viewModel.createSession(title) { id -> onStartCapture(id) }
            },
        )
    }

    renaming?.let { session ->
        TextPromptDialog(
            title = "Rename scan",
            label = "Book title",
            supportingText = null,
            initialValue = session.title,
            confirmLabel = "Save name",
            onDismiss = { renaming = null },
            onConfirm = { title ->
                renaming = null
                viewModel.renameSession(session.id, title)
            },
        )
    }

    deleting?.let { session ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${session.title}\"?") },
            text = {
                Text(
                    "This permanently deletes ${session.pageCount} scanned page(s). " +
                        "Any PDF you already exported is not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.deleteSession(session.id)
                }) { Text("Delete scan") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Keep scan") }
            },
        )
    }
}

@Composable
private fun SessionRow(
    session: ScanSession,
    onOpen: () -> Unit,
    onCapture: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                // The row's own click opens the scan; the text is announced as
                // one label rather than three separate fragments.
                modifier =
                    Modifier.weight(1f).clearAndSetSemantics {
                        contentDescription = session.accessibilityLabel()
                    },
            ) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${session.pageCount} page(s) · ${session.updatedAtEpochMs.asDateText()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (session.recovered) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Icon as well as colour: the warning must not depend on
                        // the user distinguishing red text from grey.
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text(
                            "Recovered — check the page order",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            IconButton(onClick = onCapture) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Add pages to ${session.title}")
            }

            // Rename and delete live behind an overflow menu: a delete button
            // sitting next to the frequently-tapped capture button is a
            // mis-tap away from destroying a scan.
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options for ${session.title}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete scan", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    supportingText: String?,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                supportingText = supportingText?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.semantics { },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun ScanSession.accessibilityLabel(): String =
    buildString {
        append(title)
        append(", $pageCount pages")
        append(", updated ${updatedAtEpochMs.asDateText()}")
        if (recovered) append(", recovered after an interruption, check the page order")
    }

private fun Long.asDateText(): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(this))
