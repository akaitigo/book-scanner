package dev.bookscanner.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.rememberAsyncImagePainter
import dev.bookscanner.app.ui.components.ErrorState
import dev.bookscanner.app.ui.components.LoadingState
import dev.bookscanner.app.ui.components.drawPageImage
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
 * The image and the overlay are drawn in **one** `Canvas`, in one coordinate
 * system. An earlier version placed the image with `ContentScale.Fit` and then
 * measured the *composable* bounds for the overlay; because Fit letterboxes,
 * the crop rectangle covered the empty margins as well as the picture, and the
 * handles sat at the composable's edges rather than the image's. Sharing a
 * single computed rect removes that whole class of mismatch.
 *
 * The crop stays normalized (0..1) against what the user sees, which is what
 * `PageGeometry` specifies: crop coordinates are expressed after rotation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CropEditor(
    state: PageEditorViewModel.UiState,
    onCropChanged: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
) {
    val painter = rememberAsyncImagePainter(model = state.imageFile)
    var activeHandle by remember { mutableStateOf<Handle?>(null) }
    var displayRect by remember { mutableStateOf(Rect.Zero) }

    val rotation = state.geometry.rotationDegrees
    val crop = state.editableCrop

    // Read inside the gesture without restarting it when the crop changes.
    val currentCrop by rememberUpdatedState(crop)

    Canvas(
        modifier =
            Modifier
                // The canvas deliberately fills the whole area and insets the
                // *image* instead. With padding on the canvas, each corner
                // handle sat on its edge, so the outer half of its 48 dp touch
                // region fell outside the canvas and never received the touch —
                // on a Pixel 7 the handle simply could not be grabbed.
                .fillMaxSize()
                // Without this, dragging a crop corner near the left or right
                // edge is swallowed by the system back gesture — verified on a
                // Pixel 7, where the drag closed the screen instead of cropping.
                // Android caps the excluded height per edge, so the padding
                // above also matters: it keeps the handles off the very edge.
                .systemGestureExclusion()
                // Keyed on the display rect only. Keying on the crop as well
                // restarted this block the instant the first drag event changed
                // it, cancelling the gesture — a drag across the screen moved
                // the corner by one pixel and stopped.
                .pointerInput(displayRect) {
                    detectDragGestures(
                        onDragStart = { position ->
                            activeHandle =
                                nearestHandle(
                                    position = position,
                                    crop = currentCrop,
                                    bounds = displayRect,
                                    touchRadiusPx = HANDLE_TOUCH_RADIUS.toPx(),
                                )
                        },
                        onDragEnd = { activeHandle = null },
                        onDragCancel = { activeHandle = null },
                    ) { change, amount ->
                        val handle = activeHandle ?: return@detectDragGestures
                        if (displayRect.width <= 0f || displayRect.height <= 0f) return@detectDragGestures
                        change.consume()
                        val moved =
                            handle.apply(
                                currentCrop,
                                amount.x / displayRect.width,
                                amount.y / displayRect.height,
                            )
                        onCropChanged(moved.left, moved.top, moved.right, moved.bottom)
                    }
                }.testTag(PageEditorTags.IMAGE),
    ) {
        // The image and the overlay share one rect, which is the whole point:
        // an earlier version measured the composable's bounds instead, and a
        // fitted image is letterboxed, so the crop covered the margins too.
        val display = drawPageImage(painter, rotation, inset = EDITOR_PADDING.toPx())
        if (display.width <= 0f || display.height <= 0f) return@Canvas
        displayRect = display

        val cropRect =
            Rect(
                left = display.left + crop.left * display.width,
                top = display.top + crop.top * display.height,
                right = display.left + crop.right * display.width,
                bottom = display.top + crop.bottom * display.height,
            )

        // Four rectangles around the kept area rather than a punched hole:
        // BlendMode.Clear needs an offscreen compositing layer to punch through,
        // and without one it silently did nothing here.
        val dim = Color.Black.copy(alpha = 0.55f)
        drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, cropRect.top))
        drawRect(
            dim,
            topLeft = Offset(0f, cropRect.bottom),
            size = Size(size.width, (size.height - cropRect.bottom).coerceAtLeast(0f)),
        )
        drawRect(dim, topLeft = Offset(0f, cropRect.top), size = Size(cropRect.left, cropRect.height))
        drawRect(
            dim,
            topLeft = Offset(cropRect.right, cropRect.top),
            size = Size((size.width - cropRect.right).coerceAtLeast(0f), cropRect.height),
        )

        drawRect(
            color = Color.White,
            topLeft = cropRect.topLeft,
            size = cropRect.size,
            style = Stroke(width = CROP_BORDER_WIDTH.toPx()),
        )
        listOf(cropRect.topLeft, cropRect.topRight, cropRect.bottomLeft, cropRect.bottomRight).forEach { corner ->
            drawCircle(color = Color.Black.copy(alpha = 0.5f), radius = HANDLE_RADIUS.toPx() * 1.3f, center = corner)
            drawCircle(color = Color.White, radius = HANDLE_RADIUS.toPx(), center = corner)
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
    if (bounds.width <= 0f || bounds.height <= 0f) return null
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

/** Padding around the image. Also keeps the corner handles off the very screen
 * edge, where the system back gesture would otherwise claim them. */
private val EDITOR_PADDING = 24.dp

private val CROP_BORDER_WIDTH = 2.dp

/** Visible handle radius. Drawn in px, converted from dp at draw time. */
private val HANDLE_RADIUS = 10.dp

/**
 * Half of the minimum 48dp touch target: a drag starting anywhere within this
 * distance of a corner grabs that corner. The visible dot is smaller than its
 * hit region on purpose — a 48dp dot would hide the page underneath.
 */
private val HANDLE_TOUCH_RADIUS = 24.dp
