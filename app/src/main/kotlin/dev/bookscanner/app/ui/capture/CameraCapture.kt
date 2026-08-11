package dev.bookscanner.app.ui.capture

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import dev.bookscanner.core.contracts.GrayscaleImage
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
    /**
     * Preview-resolution frames for auto-capture.
     *
     * KEEP_ONLY_LATEST because a backlog is worse than useless here: deciding
     * "is the page still now?" from a frame that is 400 ms old would fire the
     * shutter after the user has already moved on.
     */
    val imageAnalysis: ImageAnalysis =
        ImageAnalysis
            .Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector
                    .Builder()
                    .setResolutionStrategy(
                        // Small on purpose: stillness and page edges are both
                        // large-scale, and this runs on every frame.
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    ).build(),
            ).build()

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
                controller.imageAnalysis,
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

/**
 * The luma plane of a YUV frame, as a [GrayscaleImage].
 *
 * `YUV_420_888` plane 0 *is* the luminance the detector wants, so this is a
 * copy rather than a colour conversion — the row stride is usually wider than
 * the image, which is the only thing to be careful about.
 */
internal fun ImageProxy.lumaToGrayscale(): GrayscaleImage {
    val plane = planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride

    val out = ByteArray(width * height)
    if (pixelStride == 1 && rowStride == width) {
        buffer.get(out, 0, out.size)
        return GrayscaleImage(width, height, out)
    }

    val row = ByteArray(rowStride)
    var offset = 0
    for (y in 0 until height) {
        val remaining = buffer.remaining()
        if (remaining <= 0) break
        val toRead = minOf(rowStride, remaining)
        buffer.get(row, 0, toRead)
        var x = 0
        var index = 0
        while (x < width && index < toRead) {
            out[offset + x] = row[index]
            x++
            index += pixelStride
        }
        offset += width
    }
    return GrayscaleImage(width, height, out)
}
