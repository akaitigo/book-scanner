package dev.bookscanner.app.ui.pages

import dev.bookscanner.app.FakeScanRepository
import dev.bookscanner.app.MainDispatcherRule
import dev.bookscanner.app.TestFailure
import dev.bookscanner.core.contracts.EngineId
import dev.bookscanner.core.contracts.ExportPage
import dev.bookscanner.core.contracts.PdfExporter
import dev.bookscanner.core.contracts.ScanSession
import dev.bookscanner.core.contracts.SessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageListViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeScanRepository()

    /** Records what it was asked to export and can be made to block or fail. */
    private class FakeExporter(
        private val failWith: Throwable? = null,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : PdfExporter {
        override val engine = EngineId.PRODUCTION
        var exported: List<ExportPage> = emptyList()
            private set
        var wroteBytes = 0
            private set

        override suspend fun export(
            pages: List<ExportPage>,
            output: OutputStream,
            onProgress: (Int, Int) -> Unit,
        ) {
            failWith?.let { throw it }
            exported = pages
            pages.forEachIndexed { index, _ ->
                gate?.await()
                output.write(index)
                wroteBytes++
                onProgress(index + 1, pages.size)
            }
        }
    }

    /**
     * Stands in for the SAF document: it exists as soon as the user picks a
     * name, and must be removed if the export does not complete.
     */
    private class RecordingTarget : PageListViewModel.ExportTarget {
        val stream = ByteArrayOutputStream()
        var opened = 0
            private set
        var discarded = 0
            private set

        override fun openOutput(): OutputStream {
            opened++
            return stream
        }

        override fun discard() {
            discarded++
        }
    }

    private fun viewModel(
        session: ScanSession,
        exporter: PdfExporter = FakeExporter(),
    ) = PageListViewModel(session.id, repository, exporter)

    @Test
    fun `pages load in document order`() =
        runTest {
            val session = repository.seed(pageCount = 3)
            val viewModel = viewModel(session)
            advanceUntilIdle()

            assertContentEquals(
                session.pages.map { it.id },
                viewModel.state.value.pages
                    .map { it.id },
            )
            assertEquals(session.title, viewModel.state.value.title)
        }

    @Test
    fun `a missing session becomes a recoverable state`() =
        runTest {
            val viewModel = PageListViewModel(SessionId("gone"), repository, FakeExporter())
            advanceUntilIdle()

            assertNotNull(viewModel.state.value.loadError)
            assertTrue(
                viewModel.state.value.pages
                    .isEmpty(),
            )
        }

    @Test
    fun `a load failure names the cause and can be retried`() =
        runTest {
            val session = repository.seed(pageCount = 2)
            repository.failNext = TestFailure("storage offline")
            val viewModel = viewModel(session)
            advanceUntilIdle()

            val error = assertNotNull(viewModel.state.value.loadError)
            assertTrue(error.contains("storage offline"), "got: $error")

            viewModel.refresh()
            advanceUntilIdle()
            assertNull(viewModel.state.value.loadError)
            assertEquals(2, viewModel.state.value.pages.size)
        }

    // ---- selection ----

    @Test
    fun `selection toggles and clears`() =
        runTest {
            val session = repository.seed(pageCount = 3)
            val viewModel = viewModel(session)
            advanceUntilIdle()
            val first = session.pages.first().id

            viewModel.toggleSelection(first)
            assertTrue(viewModel.state.value.selectionMode)
            assertEquals(setOf(first), viewModel.state.value.selection)

            viewModel.toggleSelection(first)
            assertTrue(!viewModel.state.value.selectionMode)

            viewModel.selectAll()
            assertEquals(3, viewModel.state.value.selection.size)

            viewModel.clearSelection()
            assertTrue(
                viewModel.state.value.selection
                    .isEmpty(),
            )
        }

    @Test
    fun `deleting selected pages removes exactly those pages`() =
        runTest {
            val session = repository.seed(pageCount = 4)
            val viewModel = viewModel(session)
            advanceUntilIdle()
            val doomed = setOf(session.pages[1].id, session.pages[3].id)

            doomed.forEach(viewModel::toggleSelection)
            viewModel.deleteSelected()
            advanceUntilIdle()

            assertContentEquals(
                listOf(session.pages[0].id, session.pages[2].id),
                viewModel.state.value.pages
                    .map { it.id },
            )
            assertTrue(
                viewModel.state.value.selection
                    .isEmpty(),
                "selection should reset after deleting",
            )
        }

    @Test
    fun `deleting with nothing selected does nothing`() =
        runTest {
            val session = repository.seed(pageCount = 2)
            val viewModel = viewModel(session)
            advanceUntilIdle()

            viewModel.deleteSelected()
            advanceUntilIdle()

            assertEquals(2, viewModel.state.value.pages.size)
        }

    @Test
    fun `a stale selection is dropped when the page disappears`() =
        runTest {
            val session = repository.seed(pageCount = 2)
            val viewModel = viewModel(session)
            advanceUntilIdle()

            viewModel.toggleSelection(session.pages[0].id)
            repository.removePages(session.id, setOf(session.pages[0].id))
            viewModel.refresh()
            advanceUntilIdle()

            assertTrue(
                viewModel.state.value.selection
                    .isEmpty(),
            )
        }

    // ---- reordering ----

    @Test
    fun `reorder mode is explicit and clears any selection`() =
        runTest {
            val session = repository.seed(pageCount = 2)
            val viewModel = viewModel(session)
            advanceUntilIdle()

            viewModel.toggleSelection(session.pages[0].id)
            viewModel.startReordering()

            assertTrue(viewModel.state.value.reordering)
            assertTrue(
                viewModel.state.value.selection
                    .isEmpty(),
                "select and reorder must not be active at once — both bind to long-press",
            )

            viewModel.stopReordering()
            assertTrue(!viewModel.state.value.reordering)
        }

    @Test
    fun `dragging reorders the visible list immediately and saves once on drop`() =
        runTest {
            val session = repository.seed(pageCount = 4)
            val viewModel = viewModel(session)
            advanceUntilIdle()

            // A drag crossing three items must not be three writes.
            viewModel.movePage(0, 1)
            viewModel.movePage(1, 2)
            viewModel.movePage(2, 3)
            assertEquals(0, repository.reorderCount, "nothing should be persisted mid-drag")

            viewModel.commitOrder()
            advanceUntilIdle()

            assertEquals(1, repository.reorderCount)
            assertContentEquals(
                listOf(session.pages[1].id, session.pages[2].id, session.pages[3].id, session.pages[0].id),
                repository.current(session.id).pages.map { it.id },
            )
        }

    @Test
    fun `nudging a page moves it one position and saves`() =
        runTest {
            val session = repository.seed(pageCount = 3)
            val viewModel = viewModel(session)
            advanceUntilIdle()

            viewModel.nudgePage(index = 2, offset = -1)
            advanceUntilIdle()

            assertContentEquals(
                listOf(session.pages[0].id, session.pages[2].id, session.pages[1].id),
                repository.current(session.id).pages.map { it.id },
            )
        }

    @Test
    fun `nudging past either end is ignored`() =
        runTest {
            val session = repository.seed(pageCount = 2)
            val viewModel = viewModel(session)
            advanceUntilIdle()

            viewModel.nudgePage(index = 0, offset = -1)
            viewModel.nudgePage(index = 1, offset = +1)
            advanceUntilIdle()

            assertEquals(0, repository.reorderCount)
            assertContentEquals(
                session.pages.map { it.id },
                viewModel.state.value.pages
                    .map { it.id },
            )
        }

    @Test
    fun `a failed reorder reloads the real order instead of leaving a lie on screen`() =
        runTest {
            val session = repository.seed(pageCount = 3)
            val viewModel = viewModel(session)
            advanceUntilIdle()

            viewModel.movePage(0, 2)
            repository.failNext = TestFailure("read-only storage")
            viewModel.commitOrder()
            advanceUntilIdle()

            assertNotNull(viewModel.state.value.errorMessage)
            assertContentEquals(
                session.pages.map { it.id },
                viewModel.state.value.pages
                    .map { it.id },
                "the UI must fall back to the stored order",
            )
        }

    // ---- export ----

    @Test
    fun `export sends every page with its geometry, in order`() =
        runTest {
            val session = repository.seed(pageCount = 3)
            val exporter = FakeExporter()
            val viewModel = viewModel(session, exporter)
            advanceUntilIdle()

            viewModel.export(RecordingTarget())
            advanceUntilIdle()

            assertEquals(3, exporter.exported.size)
            assertContentEquals(
                viewModel.state.value.pages
                    .map { it.file },
                exporter.exported.map { it.imageFile },
            )
            assertTrue(viewModel.state.value.exportedSuccessfully)
            assertNull(viewModel.state.value.exportProgress)
        }

    @Test
    fun `export of an empty session is refused with an explanation`() =
        runTest {
            val session = repository.seed(pageCount = 0)
            val viewModel = viewModel(session)
            advanceUntilIdle()

            viewModel.export(RecordingTarget())
            advanceUntilIdle()

            val message = assertNotNull(viewModel.state.value.errorMessage)
            assertTrue(message.contains("at least one page"), "got: $message")
        }

    @Test
    fun `export progress is reported and cleared`() =
        runTest {
            val session = repository.seed(pageCount = 2)
            val gate = CompletableDeferred<Unit>()
            val viewModel = viewModel(session, FakeExporter(gate = gate))
            advanceUntilIdle()

            viewModel.export(RecordingTarget())
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.exportProgress, "the dialog should be up while exporting")

            gate.complete(Unit)
            advanceUntilIdle()
            assertNull(viewModel.state.value.exportProgress)
        }

    @Test
    fun `a successful export keeps the document`() =
        runTest {
            val session = repository.seed(pageCount = 2)
            val target = RecordingTarget()
            val viewModel = viewModel(session)
            advanceUntilIdle()

            viewModel.export(target)
            advanceUntilIdle()

            assertEquals(0, target.discarded, "a finished export must not delete its own output")
        }

    @Test
    fun `a failed export deletes the partial document`() =
        runTest {
            val session = repository.seed(pageCount = 2)
            val target = RecordingTarget()
            val viewModel = viewModel(session, FakeExporter(failWith = TestFailure("disk full")))
            advanceUntilIdle()

            viewModel.export(target)
            advanceUntilIdle()

            // SAF created the file when the user chose a name, so a failure
            // that left it behind would put an unopenable PDF in Downloads.
            assertEquals(1, target.discarded)

            val message = assertNotNull(viewModel.state.value.errorMessage)
            assertTrue(message.contains("disk full"), "got: $message")
            assertNull(viewModel.state.value.exportProgress)
            assertTrue(!viewModel.state.value.exportedSuccessfully)
            assertEquals(2, repository.current(session.id).pageCount)
        }

    @Test
    fun `cancelling an export stops it and dismisses the progress dialog`() =
        runTest {
            val session = repository.seed(pageCount = 5)
            val gate = CompletableDeferred<Unit>()
            val exporter = FakeExporter(gate = gate)
            val target = RecordingTarget()
            val viewModel = viewModel(session, exporter)
            advanceUntilIdle()

            viewModel.export(target)
            advanceUntilIdle()

            viewModel.cancelExport()
            advanceUntilIdle()

            assertNull(viewModel.state.value.exportProgress)
            assertTrue(!viewModel.state.value.exportedSuccessfully)
            assertEquals(0, exporter.wroteBytes, "a cancelled export should stop writing")
            assertEquals(1, target.discarded, "a cancelled export must not leave a truncated PDF behind")
        }

    @Test
    fun `a second export while one is running is ignored`() =
        runTest {
            val session = repository.seed(pageCount = 3)
            val gate = CompletableDeferred<Unit>()
            val viewModel = viewModel(session, FakeExporter(gate = gate))
            advanceUntilIdle()

            val target = RecordingTarget()
            viewModel.export(target)
            advanceUntilIdle()
            viewModel.export(target)
            advanceUntilIdle()

            assertEquals(1, target.opened)
            gate.complete(Unit)
            advanceUntilIdle()
        }
}
