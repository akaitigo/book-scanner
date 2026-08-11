package dev.bookscanner.app.ui.pages

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.bookscanner.app.ui.components.EmptyState
import dev.bookscanner.app.ui.components.ErrorState
import dev.bookscanner.app.ui.components.LoadingState
import dev.bookscanner.core.contracts.PageId

object PageListTags {
    const val GRID = "page-grid"
    const val EXPORT = "page-export"
    const val EXPORT_PROGRESS = "page-export-progress"
    const val REORDER_DONE = "page-reorder-done"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageListScreen(
    viewModel: PageListViewModel,
    onBack: () -> Unit,
    onAddPages: () -> Unit,
    onEditPage: (PageId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var viewportHeight by remember { mutableIntStateOf(0) }

    val gridState = rememberLazyGridState()
    val reorderState =
        rememberGridReorderState(
            gridState = gridState,
            onMove = viewModel::movePage,
            onDrop = viewModel::commitOrder,
        )

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME)) { uri ->
            if (uri != null) {
                viewModel.export {
                    requireNotNull(context.contentResolver.openOutputStream(uri)) {
                        "Could not open the chosen file for writing"
                    }
                }
            }
        }

    // System back leaves a mode rather than the screen, matching the visible
    // Close/Done affordances. Registered through BackHandler so predictive back
    // keeps working.
    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }
    BackHandler(enabled = state.reordering && !state.selectionMode) { viewModel.stopReordering() }

    LaunchedEffect(Unit) { viewModel.refresh() }

    state.errorMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeError()
        }
    }
    if (state.exportedSuccessfully) {
        LaunchedEffect(Unit) {
            snackbarHostState.showSnackbar("PDF saved")
            viewModel.consumeExportSuccess()
        }
    }

    Scaffold(
        topBar = {
            when {
                state.selectionMode -> {
                    TopAppBar(
                        title = { Text("${state.selection.size} selected") },
                        navigationIcon = {
                            IconButton(onClick = viewModel::clearSelection) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                            }
                        },
                        actions = {
                            IconButton(onClick = viewModel::selectAll) {
                                Icon(Icons.Default.SelectAll, contentDescription = "Select all pages")
                            }
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete selected pages")
                            }
                        },
                    )
                }

                state.reordering -> {
                    TopAppBar(
                        title = { Text("Reorder pages") },
                        navigationIcon = {
                            IconButton(
                                onClick = viewModel::stopReordering,
                                modifier = Modifier.testTag(PageListTags.REORDER_DONE),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Finish reordering")
                            }
                        },
                    )
                }

                else -> {
                    TopAppBar(
                        title = { Text(state.title.ifBlank { "Pages" }) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to scans")
                            }
                        },
                        actions = {
                            IconButton(onClick = onAddPages) {
                                Icon(Icons.Default.Add, contentDescription = "Add pages")
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Reorder pages") },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.startReordering()
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!state.selectionMode && !state.reordering && state.pages.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { exportLauncher.launch(defaultFileName(state.title)) },
                    icon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) },
                    text = { Text("Export PDF") },
                    modifier = Modifier.testTag(PageListTags.EXPORT),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.recovered && state.loadError == null) {
                RecoveredBanner()
            }
            if (state.reordering) {
                Text(
                    "Drag a page to move it, or use the arrows on each page.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(12.dp),
                )
            }

            when {
                state.loadError != null -> {
                    ErrorState(
                        message = requireNotNull(state.loadError),
                        onRetry = viewModel::refresh,
                        secondaryLabel = "Back to scans",
                        onSecondary = onBack,
                    )
                }

                state.loading && state.pages.isEmpty() -> {
                    LoadingState("Loading pages…")
                }

                state.pages.isEmpty() -> {
                    EmptyState(
                        title = "No pages yet",
                        body = "Capture the book's pages with the camera, or import images from this device.",
                        actionLabel = "Capture pages",
                        onAction = onAddPages,
                    )
                }

                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        // Bottom room for the FAB so the last row is reachable.
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .onSizeChanged { viewportHeight = it.height }
                                .then(
                                    // Drag reorder is live only in reorder mode.
                                    // Outside it, long-press means "select", the
                                    // Android convention — binding both to the
                                    // same gesture made neither reliable.
                                    if (state.reordering) {
                                        Modifier.reorderable(reorderState) { viewportHeight }
                                    } else {
                                        Modifier
                                    },
                                ).testTag(PageListTags.GRID),
                    ) {
                        itemsIndexed(state) { index, item ->
                            val dragging = reorderState.draggingKey == item.id.value
                            PageCell(
                                item = item,
                                index = index,
                                pageCount = state.pages.size,
                                selected = item.id in state.selection,
                                selectionMode = state.selectionMode,
                                reordering = state.reordering,
                                dragging = dragging,
                                modifier =
                                    Modifier.graphicsLayer {
                                        if (dragging) {
                                            translationX = reorderState.dragOffset.x
                                            translationY = reorderState.dragOffset.y
                                            scaleX = DRAG_SCALE
                                            scaleY = DRAG_SCALE
                                        }
                                    },
                                onClick = {
                                    when {
                                        state.selectionMode -> viewModel.toggleSelection(item.id)
                                        state.reordering -> Unit
                                        else -> onEditPage(item.id)
                                    }
                                },
                                onLongClick = {
                                    if (!state.reordering) viewModel.toggleSelection(item.id)
                                },
                                onMoveEarlier = { viewModel.nudgePage(index, -1) },
                                onMoveLater = { viewModel.nudgePage(index, +1) },
                            )
                        }
                    }
                }
            }
        }

        state.exportProgress?.let { progress ->
            AlertDialog(
                onDismissRequest = { },
                title = { Text("Saving PDF") },
                text = {
                    Column(modifier = Modifier.testTag(PageListTags.EXPORT_PROGRESS)) {
                        Text(
                            "Page ${progress.pagesDone} of ${progress.totalPages}",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = viewModel::cancelExport) { Text("Stop export") }
                },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${state.selection.size} page(s)?") },
            text = { Text("These scanned pages are removed from this scan permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteSelected()
                }) { Text("Delete pages") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep pages") } },
        )
    }
}

/** `items` with the index, so each cell can label and move itself. */
private fun androidx.compose.foundation.lazy.grid.LazyGridScope.itemsIndexed(
    state: PageListViewModel.UiState,
    content: @Composable (index: Int, item: PageListViewModel.PageItem) -> Unit,
) {
    items(state.pages, key = { it.id.value }) { item ->
        content(state.pages.indexOfFirst { it.id == item.id }, item)
    }
}

@Composable
private fun RecoveredBanner() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(12.dp),
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            "This scan was recovered after an interruption. Check the page order before exporting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageCell(
    item: PageListViewModel.PageItem,
    index: Int,
    pageCount: Int,
    selected: Boolean,
    selectionMode: Boolean,
    reordering: Boolean,
    dragging: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
) {
    Column(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(
                        if (selected) {
                            Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                        } else {
                            Modifier
                        },
                    ).alpha(if (dragging) DRAG_ALPHA else 1f)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .semantics {
                        contentDescription = cellLabel(index, pageCount, selected, selectionMode)
                        this.selected = selected
                    },
        ) {
            AsyncImage(
                model = item.file,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clearAndSetSemantics { },
            )
            if (selected) {
                // Shape as well as colour, so selection is not conveyed by the
                // border colour alone.
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(24.dp)
                            .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(12.dp)),
                )
            }
        }

        if (reordering) {
            // The non-gesture path: dragging is convenient, but it must not be
            // the only way to reorder.
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onMoveEarlier, enabled = index > 0) {
                    Icon(
                        Icons.Default.KeyboardArrowLeft,
                        contentDescription = "Move page ${index + 1} earlier",
                    )
                }
                IconButton(onClick = onMoveLater, enabled = index < pageCount - 1) {
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = "Move page ${index + 1} later",
                    )
                }
            }
        }
    }
}

private fun cellLabel(
    index: Int,
    pageCount: Int,
    selected: Boolean,
    selectionMode: Boolean,
): String =
    buildString {
        append("Page ${index + 1} of $pageCount")
        when {
            selected -> append(", selected")
            selectionMode -> append(", not selected")
        }
    }

private fun defaultFileName(title: String): String {
    val safe = title.replace(Regex("[^A-Za-z0-9-_ ]"), "").trim().ifBlank { "scan" }
    return "$safe.pdf"
}

private const val PDF_MIME = "application/pdf"
private const val DRAG_SCALE = 1.05f
private const val DRAG_ALPHA = 0.85f
