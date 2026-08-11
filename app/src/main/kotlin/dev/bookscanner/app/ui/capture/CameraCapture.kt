package dev.bookscanner.app.ui.capture

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Holds the CameraX use cases for the capture screen.
 *
 * Captures are taken **in memory** rather than to a file: the bytes go
 * straight to the normalizer, which decides whether they can be stored
 * verbatim. Routing through a temporary file would mean writing every page
 * twice for no benefit.
 */
class CameraCaptureController {
    val imageCapture: ImageCapture =
        ImageCapture
            .Builder()
            // Book pages are static subjects and detail matters more than
            // shutter latency for OCR later; MINIMIZE_LATENCY would trade
            // resolution for speed we do not need.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

    /**
     * Takes one photo and returns its JPEG bytes.
     *
     * @throws ImageCaptureException if the device fails the capture.
     */
    suspend fun capture(executor: Executor): ByteArray =
        suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                executor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bytes =
                            try {
                                image.toJpegBytes()
                            } catch (error: Throwable) {
                                image.close()
                                continuation.resumeWith(Result.failure(error))
                                return
                            }
                        image.close()
                        continuation.resume(bytes)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWith(Result.failure(exception))
                    }
                },
            )
        }

    private fun ImageProxy.toJpegBytes(): ByteArray {
        // ImageCapture's default output format is JPEG, so plane 0 already
        // holds the encoded file — including its EXIF orientation tag, which
        // the normalizer turns into page geometry.
        val buffer = planes[0].buffer
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
}

@Composable
fun CameraPreview(
    controller: CameraCaptureController,
    modifier: Modifier = Modifier,
    onCameraError: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }

    AndroidView(factory = { previewView }, modifier = modifier)

    LaunchedEffect(controller, lifecycleOwner) {
        runCatching {
            val provider = context.awaitCameraProvider()
            val preview = Preview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                controller.imageCapture,
            )
        }.onFailure(onCameraError)
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.resumeWith(Result.failure(it)) }
            },
            ContextCompat.getMainExecutor(this),
        )
    }
