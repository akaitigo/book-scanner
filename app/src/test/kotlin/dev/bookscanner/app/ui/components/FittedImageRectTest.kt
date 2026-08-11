package dev.bookscanner.app.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The crop overlay's geometry, extracted from the composable so it can be
 * tested at all.
 *
 * It is here because the original implementation measured the *composable*
 * bounds and treated them as the image: with `ContentScale.Fit` the picture is
 * letterboxed, so the crop rectangle covered the empty margins too and the
 * handles sat where the image was not. Nothing caught it, because the geometry
 * only existed inside a `@Composable`. These tests are the regression net.
 */
class FittedImageRectTest {
    private val container = Size(1000f, 2000f)

    /** 24 dp at the Pixel 7's 420 dpi (2.625x). */
    private val editorPaddingPx = 63f

    /** Android's back-gesture strip is 20 dp a side by default. */
    private val backGestureStripPx = 52.5f

    private fun assertRect(
        expected: Rect,
        actual: Rect,
        tolerance: Float = 0.01f,
    ) {
        assertEquals(expected.left, actual.left, tolerance, "left")
        assertEquals(expected.top, actual.top, tolerance, "top")
        assertEquals(expected.right, actual.right, tolerance, "right")
        assertEquals(expected.bottom, actual.bottom, tolerance, "bottom")
    }

    @Test
    fun `a landscape image in a portrait container is letterboxed, not stretched`() {
        // 4:3 landscape in a 1:2 portrait container: width-limited.
        val rect = fittedImageRect(container, imageWidth = 4000f, imageHeight = 3000f, rotationDegrees = 0)

        assertRect(Rect(0f, 625f, 1000f, 1375f), rect)
        assertEquals(4f / 3f, rect.width / rect.height, 0.001f, "aspect ratio must be preserved")
        assertTrue(rect.height < container.height, "there must be letterbox margin, which is the whole point")
    }

    @Test
    fun `a portrait image in a taller container is width-limited`() {
        // 3:4 image, 1:2 container — the container is the more elongated of the
        // two, so width binds and the letterbox margin is top and bottom.
        val rect = fittedImageRect(container, imageWidth = 3000f, imageHeight = 4000f, rotationDegrees = 0)

        assertRect(Rect(0f, 333.33f, 1000f, 1666.67f), rect)
        assertEquals(3f / 4f, rect.width / rect.height, 0.001f)
    }

    @Test
    fun `a quarter turn swaps the fitted extent`() {
        // The same landscape page, rotated 90, is displayed portrait — so it
        // becomes height-limited rather than width-limited.
        val upright = fittedImageRect(container, 4000f, 3000f, rotationDegrees = 0)
        val turned = fittedImageRect(container, 4000f, 3000f, rotationDegrees = 90)

        assertEquals(3f / 4f, turned.width / turned.height, 0.001f)
        assertTrue(turned.height > upright.height, "a quarter turn should use more of the container's long axis")
        assertRect(Rect(0f, 333.33f, 1000f, 1666.67f), turned)
    }

    @Test
    fun `270 degrees fits the same as 90`() {
        assertRect(
            fittedImageRect(container, 4000f, 3000f, 90),
            fittedImageRect(container, 4000f, 3000f, 270),
        )
    }

    @Test
    fun `180 degrees fits the same as 0`() {
        assertRect(
            fittedImageRect(container, 4000f, 3000f, 0),
            fittedImageRect(container, 4000f, 3000f, 180),
        )
    }

    @Test
    fun `the fitted rect is always centred in the container`() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val rect = fittedImageRect(container, 4080f, 3072f, rotation)
            assertEquals(container.width / 2f, rect.center.x, 0.01f, "rotation=$rotation centre x")
            assertEquals(container.height / 2f, rect.center.y, 0.01f, "rotation=$rotation centre y")
        }
    }

    @Test
    fun `the fitted rect never exceeds the container`() {
        listOf(
            Triple(4080f, 3072f, 0),
            Triple(4080f, 3072f, 90),
            Triple(100f, 8000f, 0),
            Triple(8000f, 100f, 270),
        ).forEach { (width, height, rotation) ->
            val rect = fittedImageRect(container, width, height, rotation)
            assertTrue(rect.width <= container.width + 0.01f, "$width x $height @$rotation overflows width")
            assertTrue(rect.height <= container.height + 0.01f, "$width x $height @$rotation overflows height")
        }
    }

    @Test
    fun `a real Pixel 7 capture fills the editor width, so the handles need the padding`() {
        // 4080x3072 from the device, quarter-turned by EXIF normalization, in
        // the editor's content area (1080 px wide minus 24 dp padding a side at
        // 2.625x density = 996 px).
        val area = Size(996f, 1400f)
        val rect = fittedImageRect(area, 4080f, 3072f, rotationDegrees = 90)

        assertEquals(3072f / 4080f, rect.width / rect.height, 0.001f)
        assertTrue(rect.height <= area.height + 0.01f)

        // It fills the width exactly, so the left/right handles sit at the
        // canvas edge. The canvas is inset by 24 dp (63 px), which is what
        // keeps them clear of the ~20 dp system back-gesture strip; on the
        // device, dragging at x=30 closed the screen instead of cropping.
        assertEquals(0f, rect.left, 0.01f)
        val handleXOnScreen = editorPaddingPx + rect.left
        assertTrue(
            handleXOnScreen > backGestureStripPx,
            "handle at ${handleXOnScreen}px would fall inside the back-gesture strip",
        )
    }

    @Test
    fun `degenerate inputs yield an empty rect rather than dividing by zero`() {
        assertEquals(Rect.Zero, fittedImageRect(Size.Zero, 100f, 100f, 0))
        assertEquals(Rect.Zero, fittedImageRect(container, 0f, 100f, 0))
        assertEquals(Rect.Zero, fittedImageRect(container, 100f, 0f, 0))
        assertEquals(Rect.Zero, fittedImageRect(Size(-1f, 10f), 100f, 100f, 0))
    }
}
