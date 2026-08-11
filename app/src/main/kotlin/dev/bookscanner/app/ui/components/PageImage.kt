package dev.bookscanner.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.compose.rememberAsyncImagePainter
import java.io.File
import kotlin.math.min

/**
 * Draws a scanned page with its stored rotation applied.
 *
 * Every place that shows a page goes through here, because orientation has
 * exactly one source of truth: the page's [PageGeometry][dev.bookscanner.core.contracts.PageGeometry].
 * The image loader is configured to ignore EXIF (see `BookScannerApplication`),
 * so nothing else rotates behind our back.
 */
@Composable
fun PageImage(
    file: File,
    rotationDegrees: Int,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val painter = rememberAsyncImagePainter(model = file)
    Canvas(
        modifier =
            if (contentDescription == null) {
                modifier
            } else {
                modifier.semantics { this.contentDescription = contentDescription }
            },
    ) {
        drawPageImage(painter, rotationDegrees)
    }
}

/**
 * Paints [painter] rotated by [rotationDegrees] into the rect it occupies once
 * fitted, and returns that rect so a caller can align an overlay with it.
 * Returns [Rect.Zero] while the image is still loading.
 *
 * [inset] shrinks the area the image is fitted into without shrinking the
 * drawing surface. The crop editor needs that: its handles sit on the image's
 * corners, and their touch regions have to stay inside the canvas.
 */
fun DrawScope.drawPageImage(
    painter: Painter,
    rotationDegrees: Int,
    inset: Float = 0f,
): Rect {
    val intrinsic = painter.intrinsicSize
    if (intrinsic.isUnspecified || intrinsic.width <= 0f || intrinsic.height <= 0f) return Rect.Zero

    val available = Size(size.width - inset * 2f, size.height - inset * 2f)
    val display =
        fittedImageRect(available, intrinsic.width, intrinsic.height, rotationDegrees)
            .translate(inset, inset)
    if (display.width <= 0f || display.height <= 0f) return Rect.Zero

    // Rotation must change the laid-out extent, not just the pixels: a
    // quarter-turned landscape page displays portrait, so the box the image is
    // painted into is the display rect transposed about its centre, then
    // rotated back onto it.
    val paintBox =
        if (isQuarterTurn(rotationDegrees)) {
            Rect(
                left = display.center.x - display.height / 2f,
                top = display.center.y - display.width / 2f,
                right = display.center.x + display.height / 2f,
                bottom = display.center.y + display.width / 2f,
            )
        } else {
            display
        }

    withTransform({ rotate(degrees = rotationDegrees.toFloat(), pivot = display.center) }) {
        translate(left = paintBox.left, top = paintBox.top) {
            with(painter) { draw(paintBox.size) }
        }
    }
    return display
}

internal fun isQuarterTurn(rotationDegrees: Int): Boolean = Math.floorMod(rotationDegrees, 180) == 90

/**
 * The rectangle an image occupies inside [container] once it has been fitted
 * and quarter-turned.
 *
 * Pure so it can be tested. The bug this replaces lived in geometry that only
 * existed inside a `@Composable`, where no test could reach it: the crop
 * overlay measured the *composable's* bounds and treated them as the image, but
 * a fitted image is letterboxed, so the crop covered the empty margins too.
 */
fun fittedImageRect(
    container: Size,
    imageWidth: Float,
    imageHeight: Float,
    rotationDegrees: Int,
): Rect {
    if (container.width <= 0f || container.height <= 0f) return Rect.Zero
    if (imageWidth <= 0f || imageHeight <= 0f) return Rect.Zero

    val quarterTurned = isQuarterTurn(rotationDegrees)
    val width = if (quarterTurned) imageHeight else imageWidth
    val height = if (quarterTurned) imageWidth else imageHeight

    val scale = min(container.width / width, container.height / height)
    val displayWidth = width * scale
    val displayHeight = height * scale
    val left = (container.width - displayWidth) / 2f
    val top = (container.height - displayHeight) / 2f
    return Rect(left, top, left + displayWidth, top + displayHeight)
}
