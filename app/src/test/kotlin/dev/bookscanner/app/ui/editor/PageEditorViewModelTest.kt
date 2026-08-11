package dev.bookscanner.app.ui.editor

import dev.bookscanner.app.FakeScanRepository
import dev.bookscanner.app.MainDispatcherRule
import dev.bookscanner.app.TestFailure
import dev.bookscanner.core.contracts.CropRect
import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.core.contracts.PageId
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
