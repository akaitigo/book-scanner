package dev.bookscanner.app.ui.editor

import dev.bookscanner.app.FakeScanRepository
import dev.bookscanner.app.MainDispatcherRule
import dev.bookscanner.app.TestFailure
import dev.bookscanner.core.contracts.CropRect
import dev.bookscanner.core.contracts.NormalizedPoint
import dev.bookscanner.core.contracts.PageBoundary
import dev.bookscanner.core.contracts.PageDetection
import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.core.contracts.PageId
import dev.bookscanner.core.contracts.ScanSession
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageEditorViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeScanRepository()

    private fun viewModel(pageIndex: Int = 0) =
        repository.seed(pageCount = 2).let { session ->
            session to PageEditorViewModel(session.id, session.pages[pageIndex].id, repository)
        }

    // ---- automatic detection ----

    private val detectedBoundary =
        PageBoundary(
            topLeft = NormalizedPoint(0.1f, 0.05f),
            topRight = NormalizedPoint(0.9f, 0.08f),
            bottomRight = NormalizedPoint(0.92f, 0.95f),
            bottomLeft = NormalizedPoint(0.08f, 0.92f),
        )

    private fun detectingViewModel(result: PageDetection): Pair<ScanSession, PageEditorViewModel> {
        val session = repository.seed(pageCount = 1)
        return session to
            PageEditorViewModel(
                sessionId = session.id,
                pageId = session.pages[0].id,
                repository = repository,
                detectPage = { _, _ -> result },
            )
    }

    @Test
    fun `detection proposes a boundary without writing it`() =
        runTest {
            val (session, viewModel) = detectingViewModel(PageDetection(detectedBoundary, confidence = 0.9f))
            advanceUntilIdle()

            viewModel.autoDetect()
            advanceUntilIdle()

            assertEquals(detectedBoundary, viewModel.state.value.geometry.boundary)
            assertTrue(viewModel.state.value.dirty, "a proposal should be savable")
            // Nothing is written until the user saves: a wrong detection must
            // be discardable.
            assertNull(
                repository
                    .current(session.id)
                    .pages[0]
                    .geometry.boundary,
            )
        }

    @Test
    fun `a detected boundary replaces any existing crop`() =
        runTest {
            val (_, viewModel) = detectingViewModel(PageDetection(detectedBoundary, confidence = 0.9f))
            advanceUntilIdle()
            viewModel.updateCrop(0.2f, 0.2f, 0.8f, 0.8f)

            viewModel.autoDetect()
            advanceUntilIdle()

            // Keeping both would crop the corrected page using coordinates
            // measured against the uncorrected one.
            assertNull(viewModel.state.value.geometry.crop)
            assertEquals(detectedBoundary, viewModel.state.value.geometry.boundary)
        }

    @Test
    fun `a failed detection says so and leaves the geometry alone`() =
        runTest {
            val (_, viewModel) = detectingViewModel(PageDetection.NOT_FOUND)
            advanceUntilIdle()
            viewModel.rotateClockwise()
            val before = viewModel.state.value.geometry

            viewModel.autoDetect()
            advanceUntilIdle()

            assertTrue(viewModel.state.value.detectionFailed)
            assertEquals(before, viewModel.state.value.geometry, "a failed detection must not disturb the edit")
            assertTrue(!viewModel.state.value.detecting)

            viewModel.consumeDetectionFailed()
            assertTrue(!viewModel.state.value.detectionFailed)
        }

    @Test
    fun `a boundary corner can be dragged`() =
        runTest {
            val (_, viewModel) = detectingViewModel(PageDetection(detectedBoundary, confidence = 0.9f))
            advanceUntilIdle()
            viewModel.autoDetect()
            advanceUntilIdle()

            viewModel.moveBoundaryCorner(cornerIndex = 0, dx = 0.05f, dy = 0.03f)

            val moved = assertNotNull(viewModel.state.value.geometry.boundary)
            assertEquals(detectedBoundary.topLeft.x + 0.05f, moved.topLeft.x, 1e-4f)
            assertEquals(detectedBoundary.topLeft.y + 0.03f, moved.topLeft.y, 1e-4f)
            // Only the dragged corner moves.
            assertEquals(detectedBoundary.topRight, moved.topRight)
            assertEquals(detectedBoundary.bottomLeft, moved.bottomLeft)
        }

    @Test
    fun `dragging a corner clamps it inside the image`() =
        runTest {
            val (_, viewModel) = detectingViewModel(PageDetection(detectedBoundary, confidence = 0.9f))
            advanceUntilIdle()
            viewModel.autoDetect()
            advanceUntilIdle()

            viewModel.moveBoundaryCorner(cornerIndex = 0, dx = -5f, dy = -5f)

            val moved = assertNotNull(viewModel.state.value.geometry.boundary)
            assertEquals(0f, moved.topLeft.x)
            assertEquals(0f, moved.topLeft.y)
        }

    @Test
    fun `a drag that would collapse the quadrilateral is refused`() =
        runTest {
            val (_, viewModel) = detectingViewModel(PageDetection(detectedBoundary, confidence = 0.9f))
            advanceUntilIdle()
            viewModel.autoDetect()
            advanceUntilIdle()
            val before = viewModel.state.value.geometry.boundary

            // Drag the top-left corner onto the bottom-right one.
            viewModel.moveBoundaryCorner(cornerIndex = 0, dx = 0.9f, dy = 0.9f)

            assertEquals(before, viewModel.state.value.geometry.boundary, "a collapsed page must not be savable")
        }

    @Test
    fun `moving a corner with no boundary does nothing`() =
        runTest {
            val (_, viewModel) = viewModel()
            advanceUntilIdle()

            viewModel.moveBoundaryCorner(cornerIndex = 0, dx = 0.1f, dy = 0.1f)

            assertNull(viewModel.state.value.geometry.boundary)
        }

    @Test
    fun `the boundary can be undone`() =
        runTest {
            val (_, viewModel) = detectingViewModel(PageDetection(detectedBoundary, confidence = 0.9f))
            advanceUntilIdle()
            viewModel.autoDetect()
            advanceUntilIdle()

            viewModel.clearBoundary()

            assertNull(viewModel.state.value.geometry.boundary)
        }

    @Test
    fun `detection is optional and the editor works without it`() =
        runTest {
            val (_, viewModel) = viewModel()
            advanceUntilIdle()

            assertTrue(!viewModel.state.value.detectionAvailable)
            // Must be a no-op rather than a crash: manual editing cannot depend
            // on detection being wired up.
            viewModel.autoDetect()
            advanceUntilIdle()
            assertNull(viewModel.state.value.geometry.boundary)
        }

    @Test
    fun `a saved boundary persists`() =
        runTest {
            val (session, viewModel) = detectingViewModel(PageDetection(detectedBoundary, confidence = 0.9f))
            advanceUntilIdle()
            viewModel.autoDetect()
            advanceUntilIdle()

            viewModel.save()
            advanceUntilIdle()

            assertEquals(
                detectedBoundary,
                repository
                    .current(session.id)
                    .pages[0]
                    .geometry.boundary,
            )
        }

    @Test
    fun `loads the page's stored geometry`() =
        runTest {
            val session = repository.seed(pageCount = 1)
            val geometry = PageGeometry(rotationDegrees = 180, crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f))
            repository.updatePageGeometry(session.id, session.pages[0].id, geometry)

            val viewModel = PageEditorViewModel(session.id, session.pages[0].id, repository)
            advanceUntilIdle()

            assertEquals(geometry, viewModel.state.value.geometry)
            assertEquals(geometry, viewModel.state.value.savedGeometry)
            assertTrue(!viewModel.state.value.dirty)
            assertNotNull(viewModel.state.value.imageFile)
        }

    @Test
    fun `a missing page becomes an error with no crash`() =
        runTest {
            val session = repository.seed(pageCount = 1)
            val viewModel = PageEditorViewModel(session.id, PageId("ghost"), repository)
            advanceUntilIdle()

            assertNotNull(viewModel.state.value.errorMessage)
            assertNull(viewModel.state.value.imageFile)
        }

    @Test
    fun `rotating marks the editor dirty without touching storage`() =
        runTest {
            val (session, viewModel) = viewModel()
            advanceUntilIdle()

            viewModel.rotateClockwise()

            assertEquals(90, viewModel.state.value.geometry.rotationDegrees)
            assertTrue(viewModel.state.value.dirty)
            assertEquals(
                PageGeometry.IDENTITY,
                repository
                    .current(session.id)
                    .pages[0]
                    .geometry,
                "nothing should be written before Save",
            )
        }

    @Test
    fun `four rotations return to the starting point and clear dirty`() =
        runTest {
            val (_, viewModel) = viewModel()
            advanceUntilIdle()

            repeat(4) { viewModel.rotateClockwise() }

            assertEquals(0, viewModel.state.value.geometry.rotationDegrees)
            assertTrue(!viewModel.state.value.dirty)
        }

    @Test
    fun `counter-clockwise rotation normalizes`() =
        runTest {
            val (_, viewModel) = viewModel()
            advanceUntilIdle()

            viewModel.rotateCounterClockwise()

            assertEquals(270, viewModel.state.value.geometry.rotationDegrees)
        }

    @Test
    fun `crop input is sanitized rather than trusted`() =
        runTest {
            val (_, viewModel) = viewModel()
            advanceUntilIdle()

            // Drag handles can produce inverted and out-of-bounds values.
            viewModel.updateCrop(left = 1.4f, top = 0.8f, right = 0.2f, bottom = -0.3f)

            val crop = assertNotNull(viewModel.state.value.geometry.crop)
            assertEquals(CropRect(0.2f, 0f, 1f, 0.8f), crop)
        }

    @Test
    fun `a degenerate crop is ignored instead of erasing the page`() =
        runTest {
            val (_, viewModel) = viewModel()
            advanceUntilIdle()
            viewModel.updateCrop(0.1f, 0.1f, 0.9f, 0.9f)
            val before = viewModel.state.value.geometry.crop

            viewModel.updateCrop(0.5f, 0.5f, 0.5f, 0.5f)

            assertEquals(before, viewModel.state.value.geometry.crop)
        }

    @Test
    fun `a full-page crop is stored as no crop at all`() =
        runTest {
            val (_, viewModel) = viewModel()
            advanceUntilIdle()

            viewModel.updateCrop(0f, 0f, 1f, 1f)

            assertNull(viewModel.state.value.geometry.crop, "a full-page crop is not a crop")
        }

    @Test
    fun `resetting the crop keeps the rotation`() =
        runTest {
            val (_, viewModel) = viewModel()
            advanceUntilIdle()
            viewModel.rotateClockwise()
            viewModel.updateCrop(0.2f, 0.2f, 0.8f, 0.8f)

            viewModel.resetCrop()

            assertNull(viewModel.state.value.geometry.crop)
            assertEquals(90, viewModel.state.value.geometry.rotationDegrees)
        }

    @Test
    fun `saving persists geometry and leaves the original file untouched`() =
        runTest {
            val (session, viewModel) = viewModel()
            advanceUntilIdle()
            val fileBefore = viewModel.state.value.imageFile

            viewModel.rotateClockwise()
            viewModel.updateCrop(0.1f, 0.2f, 0.8f, 0.9f)
            viewModel.save()
            advanceUntilIdle()

            val stored = repository.current(session.id).pages[0].geometry
            assertEquals(90, stored.rotationDegrees)
            assertEquals(CropRect(0.1f, 0.2f, 0.8f, 0.9f), stored.crop)
            assertTrue(viewModel.state.value.saved)
            assertTrue(!viewModel.state.value.dirty, "saving should clear the dirty flag")
            assertEquals(fileBefore, viewModel.state.value.imageFile, "editing must not swap the page file")
        }

    @Test
    fun `reverting restores the saved geometry`() =
        runTest {
            val (_, viewModel) = viewModel()
            advanceUntilIdle()
            viewModel.rotateClockwise()
            viewModel.updateCrop(0.1f, 0.1f, 0.5f, 0.5f)
            assertTrue(viewModel.state.value.dirty)

            viewModel.revert()

            assertEquals(PageGeometry.IDENTITY, viewModel.state.value.geometry)
            assertTrue(!viewModel.state.value.dirty)
        }

    @Test
    fun `a failed save keeps the edits so the user does not lose them`() =
        runTest {
            val (_, viewModel) = viewModel()
            advanceUntilIdle()
            viewModel.rotateClockwise()

            repository.failNext = TestFailure("write failed")
            viewModel.save()
            advanceUntilIdle()

            assertEquals("write failed", viewModel.state.value.errorMessage)
            assertEquals(90, viewModel.state.value.geometry.rotationDegrees, "the edit must survive a failed save")
            assertTrue(viewModel.state.value.dirty)
            assertTrue(!viewModel.state.value.saved)
        }

    @Test
    fun `editing one page does not disturb its neighbour`() =
        runTest {
            val session = repository.seed(pageCount = 2)
            val viewModel = PageEditorViewModel(session.id, session.pages[1].id, repository)
            advanceUntilIdle()

            viewModel.rotateClockwise()
            viewModel.save()
            advanceUntilIdle()

            assertEquals(
                PageGeometry.IDENTITY,
                repository
                    .current(session.id)
                    .pages[0]
                    .geometry,
            )
            assertEquals(
                90,
                repository
                    .current(session.id)
                    .pages[1]
                    .geometry.rotationDegrees,
            )
        }
}
