package dev.bookscanner.app.ui.capture

import dev.bookscanner.app.FakeScanRepository
import dev.bookscanner.app.MainDispatcherRule
import dev.bookscanner.app.TestFailure
import dev.bookscanner.core.contracts.EngineId
import dev.bookscanner.core.contracts.NormalizedPage
import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.core.contracts.PageImageNormalizer
import dev.bookscanner.core.session.PageIngestor
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeScanRepository()

    /** Records what was ingested so ordering can be asserted. */
    private class RecordingNormalizer(
        private val failOn: Set<String> = emptySet(),
        private val geometry: PageGeometry = PageGeometry.IDENTITY,
    ) : PageImageNormalizer {
        val received = mutableListOf<String>()

        override val engine = EngineId.PRODUCTION

        override suspend fun normalize(
            input: InputStream,
            output: OutputStream,
        ): NormalizedPage {
            val content = input.readBytes().decodeToString()
            if (content in failOn) throw TestFailure("unsupported image: $content")
            received += content
            output.write(content.toByteArray())
            return NormalizedPage(geometry = geometry, losslessCopy = true)
        }
    }

    private fun viewModel(normalizer: PageImageNormalizer): CaptureViewModel {
        val session = repository.seed(title = "Book")
        return CaptureViewModel(session.id, repository, PageIngestor(repository, normalizer))
    }

    @Test
    fun `captured frames are appended in order`() =
        runTest {
            val normalizer = RecordingNormalizer()
            val viewModel = viewModel(normalizer)
            advanceUntilIdle()

            viewModel.onFrameCaptured("one".toByteArray())
            advanceUntilIdle()
            viewModel.onFrameCaptured("two".toByteArray())
            advanceUntilIdle()

            assertContentEquals(listOf("one", "two"), normalizer.received)
            assertEquals(2, viewModel.state.value.pageCount)
        }

    @Test
    fun `a second shutter press while capturing is ignored`() =
        runTest {
            val normalizer = RecordingNormalizer()
            val viewModel = viewModel(normalizer)
            advanceUntilIdle()

            // Both calls land before the first completes; accepting the second
            // would interleave two staged pages and scramble the page order.
            viewModel.onFrameCaptured("first".toByteArray())
            viewModel.onFrameCaptured("dropped".toByteArray())
            advanceUntilIdle()

            assertContentEquals(listOf("first"), normalizer.received)
            assertEquals(1, viewModel.state.value.pageCount)
        }

    @Test
    fun `the busy flag clears after a capture`() =
        runTest {
            val viewModel = viewModel(RecordingNormalizer())
            advanceUntilIdle()

            viewModel.onFrameCaptured("page".toByteArray())
            advanceUntilIdle()

            assertTrue(!viewModel.state.value.busy)
        }

    @Test
    fun `a failed capture reports the error and does not add a page`() =
        runTest {
            val viewModel = viewModel(RecordingNormalizer(failOn = setOf("corrupt")))
            advanceUntilIdle()

            viewModel.onFrameCaptured("corrupt".toByteArray())
            advanceUntilIdle()

            assertEquals(0, viewModel.state.value.pageCount)
            assertNotNull(viewModel.state.value.errorMessage)
            assertTrue(!viewModel.state.value.busy, "a failure must not leave the shutter stuck")
        }

    @Test
    fun `a camera error clears the busy state so the shutter is usable again`() =
        runTest {
            val viewModel = viewModel(RecordingNormalizer())
            advanceUntilIdle()

            viewModel.onCaptureFailed(TestFailure("lens busy"))

            assertEquals("lens busy", viewModel.state.value.errorMessage)
            assertTrue(!viewModel.state.value.busy)
        }

    @Test
    fun `imports keep the order the picker returned`() =
        runTest {
            val normalizer = RecordingNormalizer()
            val viewModel = viewModel(normalizer)
            advanceUntilIdle()

            viewModel.onImagesPicked(listOf("a", "b", "c").map { name -> { name.byteInputStream() } })
            advanceUntilIdle()

            assertContentEquals(listOf("a", "b", "c"), normalizer.received)
            assertEquals(3, viewModel.state.value.pageCount)
        }

    @Test
    fun `one unreadable image does not abort the rest of the import`() =
        runTest {
            val normalizer = RecordingNormalizer(failOn = setOf("bad"))
            val viewModel = viewModel(normalizer)
            advanceUntilIdle()

            viewModel.onImagesPicked(listOf("a", "bad", "c").map { name -> { name.byteInputStream() } })
            advanceUntilIdle()

            assertContentEquals(listOf("a", "c"), normalizer.received)
            assertEquals(2, viewModel.state.value.pageCount)
            val message = assertNotNull(viewModel.state.value.errorMessage)
            assertTrue(message.contains("2 of 3"), "should say what was imported: $message")
        }

    @Test
    fun `an import where everything fails says so plainly`() =
        runTest {
            val viewModel = viewModel(RecordingNormalizer(failOn = setOf("x", "y")))
            advanceUntilIdle()

            viewModel.onImagesPicked(listOf("x", "y").map { name -> { name.byteInputStream() } })
            advanceUntilIdle()

            assertEquals(0, viewModel.state.value.pageCount)
            val message = assertNotNull(viewModel.state.value.errorMessage)
            assertTrue(message.contains("any of the 2"), "got: $message")
        }

    @Test
    fun `an empty pick is a no-op`() =
        runTest {
            val viewModel = viewModel(RecordingNormalizer())
            advanceUntilIdle()

            viewModel.onImagesPicked(emptyList())
            advanceUntilIdle()

            assertEquals(0, viewModel.state.value.pageCount)
            assertNull(viewModel.state.value.errorMessage)
        }

    @Test
    fun `normalizer geometry reaches the stored page`() =
        runTest {
            val geometry = PageGeometry(rotationDegrees = 90)
            val session = repository.seed(title = "Rotated")
            val viewModel =
                CaptureViewModel(
                    session.id,
                    repository,
                    PageIngestor(repository, RecordingNormalizer(geometry = geometry)),
                )
            advanceUntilIdle()

            viewModel.onFrameCaptured("page".toByteArray())
            advanceUntilIdle()

            assertEquals(
                geometry,
                repository
                    .current(session.id)
                    .pages
                    .single()
                    .geometry,
            )
        }

    @Test
    fun `the recent strip shows the newest pages first`() =
        runTest {
            val session = repository.seed(title = "Strip")
            val viewModel = CaptureViewModel(session.id, repository, PageIngestor(repository, RecordingNormalizer()))
            advanceUntilIdle()

            listOf("one", "two", "three").forEach {
                viewModel.onFrameCaptured(it.toByteArray())
                advanceUntilIdle()
            }

            val documentOrder = repository.current(session.id).pages.map { it.id }
            val stripOrder =
                viewModel.state.value.recentPages
                    .map { it.page.id }
            assertContentEquals(documentOrder.reversed(), stripOrder, "the strip should lead with the newest page")
        }
}
