package dev.bookscanner.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.bookscanner.app.ui.components.ErrorState
import dev.bookscanner.app.ui.components.LoadingState
import dev.bookscanner.core.contracts.CropRect
import kotlin.math.abs

object PageEditorTags {
    const val IMAGE = "editor-image"
    const val SAVE = "editor-save"
    const val ROTATE_CW = "editor-rotate-cw"
    const val RESET_CROP = "editor-reset-crop"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(
    viewModel: PageEditorViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDiscard by remember { mutableStateOf(false) }

    state.errorMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeError()
        }
    }
    if (state.saved) {
        LaunchedEffect(Unit) {
            viewModel.consumeSaved()
            onClose()
        }
    }

    val closeRequest = { if (state.dirty) confirmDiscard = true else onClose() }

    // System back must not silently discard unsaved crop/rotation. Routed
    // through BackHandler so the predictive-back animation still runs.
    BackHandler(enabled = state.dirty && !confirmDiscard) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit page") },
                navigationIcon = {
                    IconButton(onClick = closeRequest) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::save,
                        enabled = state.dirty && !state.saving,
                        modifier = Modifier.testTag(PageEditorTags.SAVE),
                    ) { Text("Save") }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                IconButton(onClick = viewModel::rotateCounterClockwise) {
                    Icon(Icons.Default.RotateLeft, contentDescription = "Rotate left")
                }
                IconButton(
                    onClick = viewModel::rotateClockwise,
                    modifier = Modifier.testTag(PageEditorTags.ROTATE_CW),
                ) {
                    Icon(Icons.Default.RotateRight, contentDescription = "Rotate right")
                }
                IconButton(
                    onClick = viewModel::resetCrop,
                    modifier = Modifier.testTag(PageEditorTags.RESET_CROP),
                ) {
                    Icon(Icons.Default.Crop, contentDescription = "Reset crop")
                }
                Text(
                    "Drag the corners to crop",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.loading -> {
                    LoadingState("Loading page…")
                }

                state.imageFile == null -> {
                    // An unreadable page must still offer a way out; a bare
                    // message with no control is a dead end.
                    ErrorState(
                        message = "This page could not be opened. It may have been deleted.",
                        onRetry = onClose,
                        retryLabel = "Back to pages",
                    )
                }

                else -> {
                    CropEditor(
                        state = state,
                        onCropChanged = viewModel::updateCrop,
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your crop and rotation changes will not be saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.revert()
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

/**
 * Image preview with a draggable crop rectangle.
 *
 * The crop is stored normalized (0..1), so it survives rotation, zoom and any
 * screen size. The gesture works in pixels and converts at the boundary —
 * keeping pixel maths out of the domain model.
 */
@Composable
private fun CropEditor(
    state: PageEditorViewModel.UiState,
    onCropChanged: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
) {
    var imageBounds by remember { mutableStateOf(Rect.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var activeHandle by remember { mutableStateOf<Handle?>(null) }

    val crop = state.editableCrop

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = state.imageFile,
            contentDescription = "Page preview",
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .rotatedByDegrees(state.geometry.rotationDegrees)
                    .onGloballyPositioned { coordinates ->
                        val size = coordinates.size
                        val position = coordinates.positionInParent()
                        imageBounds =
                            Rect(
                                offset = Offset(position.x, position.y),
                                size = Size(size.width.toFloat(), size.height.toFloat()),
                            )
                    }.testTag(PageEditorTags.IMAGE),
        )

        if (imageBounds.width > 0f && imageBounds.height > 0f) {
            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(imageBounds, crop) {
                            detectDragGestures(
                                onDragStart = { position ->
                                    activeHandle =
                                        nearestHandle(
                                            position = position,
                                            crop = crop,
                                            bounds = imageBounds,
                                            touchRadiusPx = HANDLE_TOUCH_RADIUS.toPx(),
                                        )
                                },
                                onDragEnd = { activeHandle = null },
                                onDragCancel = { activeHandle = null },
                            ) { change, amount ->
                                change.consume()
                                val handle = activeHandle ?: return@detectDragGestures
                                val dx = amount.x / imageBounds.width
                                val dy = amount.y / imageBounds.height
                                val moved = handle.apply(crop, dx, dy)
                                onCropChanged(moved.left, moved.top, moved.right, moved.bottom)
                            }
                        },
            ) {
                val rect =
                    Rect(
                        left = imageBounds.left + crop.left * imageBounds.width,
                        top = imageBounds.top + crop.top * imageBounds.height,
                        right = imageBounds.left + crop.right * imageBounds.width,
                        bottom = imageBounds.top + crop.bottom * imageBounds.height,
                    )

                // Dim everything outside the crop so the kept area reads at a glance.
                drawRect(color = Color.Black.copy(alpha = 0.45f), size = size)
                drawRect(
                    color = Color.Transparent,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
                )
                drawRect(
                    color = Color.White,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(width = 3f),
                )
                listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight).forEach { corner ->
                    drawCircle(color = Color.White, radius = HANDLE_RADIUS.toPx(), center = corner)
                }
            }
        }
    }
}

/** Which corner a drag is manipulating. */
private enum class Handle {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ;

    fun apply(
        crop: CropRect,
        dx: Float,
        dy: Float,
    ): CropRect =
        when (this) {
            TOP_LEFT -> CropRect.sanitized(crop.left + dx, crop.top + dy, crop.right, crop.bottom)
            TOP_RIGHT -> CropRect.sanitized(crop.left, crop.top + dy, crop.right + dx, crop.bottom)
            BOTTOM_LEFT -> CropRect.sanitized(crop.left + dx, crop.top, crop.right, crop.bottom + dy)
            BOTTOM_RIGHT -> CropRect.sanitized(crop.left, crop.top, crop.right + dx, crop.bottom + dy)
        } ?: crop
}

/**
 * The corner a drag starting at [position] should grab, or null if the drag
 * began away from every corner.
 *
 * [touchRadiusPx] is half of the 48 dp minimum target, so each corner is
 * grabbable from a full-size hit region even though its dot is drawn smaller.
 */
private fun nearestHandle(
    position: Offset,
    crop: CropRect,
    bounds: Rect,
    touchRadiusPx: Float,
): Handle? {
    val corners =
        mapOf(
            Handle.TOP_LEFT to Offset(bounds.left + crop.left * bounds.width, bounds.top + crop.top * bounds.height),
            Handle.TOP_RIGHT to Offset(bounds.left + crop.right * bounds.width, bounds.top + crop.top * bounds.height),
            Handle.BOTTOM_LEFT to
                Offset(bounds.left + crop.left * bounds.width, bounds.top + crop.bottom * bounds.height),
            Handle.BOTTOM_RIGHT to
                Offset(bounds.left + crop.right * bounds.width, bounds.top + crop.bottom * bounds.height),
        )
    return corners
        .minByOrNull { (_, corner) -> abs(corner.x - position.x) + abs(corner.y - position.y) }
        ?.takeIf { (_, corner) ->
            abs(corner.x - position.x) <= touchRadiusPx && abs(corner.y - position.y) <= touchRadiusPx
        }?.key
}

private fun Modifier.rotatedByDegrees(degrees: Int): Modifier = if (degrees == 0) this else rotate(degrees.toFloat())

/** Visible handle radius. Drawn in px, converted from dp at draw time. */
private val HANDLE_RADIUS = 10.dp

/**
 * Half of the minimum 48dp touch target: a drag starting anywhere within this
 * distance of a corner grabs that corner. The visible dot is smaller than its
 * hit region on purpose — a 48dp dot would hide the page underneath.
 */
private val HANDLE_TOUCH_RADIUS = 24.dp
