package dev.bookscanner.app.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

object CaptureTags {
    const val SHUTTER = "capture-shutter"
    const val IMPORT = "capture-import"
    const val DONE = "capture-done"
    const val PERMISSION_RATIONALE = "capture-permission-rationale"
    const val PERMISSION_DENIED = "capture-permission-denied"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    viewModel: CaptureViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val controller = remember { CameraCaptureController() }

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraGranted = granted
            permissionDenied = !granted
        }

    val pickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT_COUNT),
        ) { uris ->
            if (uris.isNotEmpty()) {
                viewModel.onImagesPicked(
                    uris.map { uri ->
                        {
                            requireNotNull(context.contentResolver.openInputStream(uri)) {
                                "Could not open the selected image"
                            }
                        }
                    },
                )
            }
        }

    state.errorMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        pageCountLabel(state.pageCount),
                        // Announced when a capture lands, so the count is not
                        // information only sighted users receive.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                },
                navigationIcon = {
                    // Without this the screen has no visible way back; system
                    // back alone is not a perceivable affordance.
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to pages",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { pickerLauncher.launch(PickVisualMediaRequest()) },
                        modifier = Modifier.testTag(CaptureTags.IMPORT),
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Import images")
                    }
                    TextButton(onClick = onDone, modifier = Modifier.testTag(CaptureTags.DONE)) {
                        Text("Done")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    cameraGranted -> {
                        CameraPreview(
                            controller = controller,
                            modifier = Modifier.fillMaxSize(),
                            onCameraError = { error -> viewModel.onCaptureFailed(error) },
                        )
                    }

                    permissionDenied -> {
                        CameraUnavailable(
                            title = "Camera access is off",
                            body =
                                "Book Scanner captures pages with the camera. You can turn camera access " +
                                    "on in Settings, or add pages from images already on this device.",
                            primaryLabel = "Try again",
                            onPrimary = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            onImport = { pickerLauncher.launch(PickVisualMediaRequest()) },
                            testTag = CaptureTags.PERMISSION_DENIED,
                        )
                    }

                    else -> {
                        // The permission is requested from a button, after the
                        // reason is on screen — not the instant the screen opens.
                        CameraUnavailable(
                            title = "Capture pages with the camera",
                            body =
                                "Book Scanner needs the camera to photograph each page. " +
                                    "Pages stay on this device — the app has no internet access at all.",
                            primaryLabel = "Allow camera",
                            onPrimary = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            onImport = { pickerLauncher.launch(PickVisualMediaRequest()) },
                            testTag = CaptureTags.PERMISSION_RATIONALE,
                        )
                    }
                }
            }

            if (state.recentPages.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.recentPages, key = { it.page.id.value }) { thumbnail ->
                        val position = state.pageCount - state.recentPages.indexOf(thumbnail)
                        AsyncImage(
                            model = thumbnail.file,
                            contentDescription = "Page $position",
                            modifier =
                                Modifier
                                    .aspectRatio(0.75f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.busy) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Text(
                            if (state.importing) "Importing images…" else "Saving page…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            scope.launch {
                                runCatching { controller.capture(ContextCompat.getMainExecutor(context)) }
                                    .onSuccess(viewModel::onFrameCaptured)
                                    .onFailure(viewModel::onCaptureFailed)
                            }
                        },
                        enabled = cameraGranted,
                        shape = CircleShape,
                        modifier =
                            Modifier
                                .size(SHUTTER_SIZE)
                                .semantics { contentDescription = "Capture page" }
                                .testTag(CaptureTags.SHUTTER),
                    ) {
                        Icon(Icons.Default.Lens, contentDescription = null, modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
    }
}

/**
 * Shown both before the permission is requested and after it is denied.
 *
 * Denial is a designed state, not an error: importing is offered as a
 * first-class alternative so the screen is never a dead end.
 */
@Composable
private fun CameraUnavailable(
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onImport: () -> Unit,
    testTag: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(24.dp).testTag(testTag),
    ) {
        Icon(
            Icons.Default.NoPhotography,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPrimary) { Text(primaryLabel) }
            OutlinedButton(onClick = onImport) { Text("Import images") }
        }
    }
}

private fun pageCountLabel(count: Int): String =
    when (count) {
        0 -> "No pages yet"
        1 -> "1 page captured"
        else -> "$count pages captured"
    }

private val SHUTTER_SIZE = 72.dp
private const val MAX_IMPORT_COUNT = 100
