package dev.bookscanner.app.ui.capture

import dev.bookscanner.app.FakeScanRepository
import dev.bookscanner.app.MainDispatcherRule
import dev.bookscanner.app.TestFailure
import dev.bookscanner.core.contracts.EngineId
import dev.bookscanner.core.contracts.GrayscaleImage
import dev.bookscanner.core.contracts.NormalizedPage
import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.core.contracts.PageImageNormalizer
import dev.bookscanner.core.session.PageIngestor
import dev.bookscanner.vision.PageSignature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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

    // ---- automatic capture ----

    /**
     * A frame with page-like fine detail. A flat frame is rejected by the
     * sharpness floor in `AutoCaptureController`, which is the point of it —
     * auto-capture once photographed a blurred floor.
     */
    private fun frame(
        level: Int,
        scene: Long = 1,
    ): GrayscaleImage {
        var state = scene or 1L
        val pixels =
            ByteArray(40 * 30) {
                state = state * 6364136223846793005L + 1442695040888963407L
                val swing = ((state ushr 33).toInt() % 41) - 20
                (level + swing).coerceIn(0, 255).toByte()
            }
        return GrayscaleImage(40, 30, pixels)
    }

    @Test
    fun `frames are ignored until auto capture is switched on`() =
        runTest {
            val viewModel = viewModel(RecordingNormalizer())
            advanceUntilIdle()

            var now = 0L
            repeat(20) {
                // A feature that fires the shutter unprompted must be asked for.
                assertTrue(!viewModel.onPreviewFrame(frame(200), now))
                now += 100
            }
        }

    @Test
    fun `holding a still frame asks for a capture`() =
        runTest {
            val viewModel = viewModel(RecordingNormalizer())
            advanceUntilIdle()
            viewModel.setAutoCapture(true)

            var now = 0L
            var asked = false
            repeat(20) {
                if (viewModel.onPreviewFrame(frame(200), now)) asked = true
                now += 100
            }

            assertTrue(asked, "a page held still should trigger a capture")
        }

    @Test
    fun `no capture is requested while a page is already being saved`() =
        runTest {
            val viewModel = viewModel(RecordingNormalizer())
            advanceUntilIdle()
            viewModel.setAutoCapture(true)

            // Occupy the pipeline, then feed perfectly still frames.
            viewModel.onFrameCaptured("page".toByteArray())
            var now = 0L
            repeat(20) {
                assertTrue(
                    !viewModel.onPreviewFrame(frame(200), now),
                    "auto capture must not stack on top of an in-flight ingest",
                )
                now += 100
            }
        }

    @Test
    fun `turning auto capture off clears its feedback`() =
        runTest {
            val viewModel = viewModel(RecordingNormalizer())
            advanceUntilIdle()
            viewModel.setAutoCapture(true)
            viewModel.onPreviewFrame(frame(200), 0)
            viewModel.onPreviewFrame(frame(200), 300)

            viewModel.setAutoCapture(false)

            assertTrue(!viewModel.state.value.autoCapture)
            assertEquals(0f, viewModel.state.value.holdProgress)
            assertNull(viewModel.state.value.previewBoundary)
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

    /**
     * A fingerprint keyed on the frame's content, standing in for the Android
     * JPEG decoder the app injects. Whether two *real* pages are told apart is
     * `PageSignatureTest`'s subject; what matters here is that the ViewModel
     * consults the fingerprint at all, and only when it fired by itself.
     */
    private fun signatureOfName(bytes: ByteArray): PageSignature {
        val name = bytes.decodeToString()
        var seed = name.hashCode().toLong() * 6364136223846793005L + 1442695040888963407L
        val pixels =
            ByteArray(128 * 96) {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                ((seed ushr 40).toInt() and 0xFF).toByte()
            }
        return PageSignature.of(GrayscaleImage(128, 96, pixels))
    }

    @Test
    fun `an automatic capture of the page already taken is skipped`() =
        runTest {
            // The reported problem: an automatic capture gives no sign it
            // happened, so the same page gets photographed twice.
            val normalizer = RecordingNormalizer()
            val viewModel = viewModel(normalizer).apply { signatureOf = { signatureOfName(it) } }
            advanceUntilIdle()

            viewModel.onAutoFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()
            viewModel.onAutoFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()

            assertContentEquals(listOf("page-200"), normalizer.received, "the repeat should not have been stored")
            assertTrue(
                viewModel.state.value.duplicateSkipped,
                "the skip has to be visible; a silent one is why this happened",
            )
            assertEquals(1, viewModel.state.value.captureCount)
        }

    @Test
    fun `reopening capture still skips the last stored page`() =
        runTest {
            val session = repository.seed(title = "Book")
            val normalizer = RecordingNormalizer()
            val first =
                CaptureViewModel(session.id, repository, PageIngestor(repository, normalizer)).apply {
                    signatureOf = { signatureOfName(it) }
                }
            advanceUntilIdle()
            first.onAutoFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()

            val reopened =
                CaptureViewModel(
                    session.id,
                    repository,
                    PageIngestor(repository, normalizer),
                    signatureOf = { signatureOfName(it) },
                    signatureOfFile = { signatureOfName("page-200".toByteArray()) },
                )
            advanceUntilIdle()
            reopened.onAutoFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()

            assertContentEquals(listOf("page-200"), normalizer.received)
            assertTrue(reopened.state.value.duplicateSkipped)
            assertEquals(1, reopened.state.value.pageCount)
        }

    @Test
    fun `deleting the last page makes its predecessor the duplicate reference`() =
        runTest {
            val session = repository.seed(title = "Book")
            val normalizer = RecordingNormalizer()
            val viewModel =
                CaptureViewModel(
                    session.id,
                    repository,
                    PageIngestor(repository, normalizer),
                    signatureOf = { signatureOfName(it) },
                )
            advanceUntilIdle()
            viewModel.onAutoFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()
            viewModel.onAutoFrameCaptured("page-201".toByteArray())
            advanceUntilIdle()

            val pages = repository.current(session.id).pages
            val page200 = pages.first()
            val page201 = pages.last()
            viewModel.signatureOfFile = { file ->
                if (file.name == page200.fileName) signatureOfName("page-200".toByteArray()) else null
            }
            viewModel.deletePage(page201)
            advanceUntilIdle()
            viewModel.onAutoFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()

            assertContentEquals(listOf("page-200", "page-201"), normalizer.received)
            assertTrue(viewModel.state.value.duplicateSkipped)
            assertEquals(1, viewModel.state.value.pageCount)
        }

    @Test
    fun `an imported page becomes the duplicate reference`() =
        runTest {
            val normalizer = RecordingNormalizer()
            val viewModel = viewModel(normalizer).apply { signatureOf = { signatureOfName(it) } }
            advanceUntilIdle()
            viewModel.onAutoFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()

            viewModel.signatureOfFile = { signatureOfName("page-201".toByteArray()) }
            viewModel.onImagesPicked(listOf({ "page-201".byteInputStream() }))
            advanceUntilIdle()
            viewModel.onAutoFrameCaptured("page-201".toByteArray())
            advanceUntilIdle()

            assertContentEquals(listOf("page-200", "page-201"), normalizer.received)
            assertTrue(viewModel.state.value.duplicateSkipped)
            assertEquals(2, viewModel.state.value.pageCount)
        }

    @Test
    fun `a late restore cannot replace the signature of a newer capture`() =
        runTest {
            val session = repository.seed(title = "Book", pageCount = 1)
            val restoreMayFinish = CompletableDeferred<Unit>()
            val normalizer = RecordingNormalizer()
            val viewModel =
                CaptureViewModel(
                    session.id,
                    repository,
                    PageIngestor(repository, normalizer),
                    signatureOf = { signatureOfName(it) },
                    signatureOfFile = {
                        restoreMayFinish.await()
                        signatureOfName("old-page".toByteArray())
                    },
                )
            runCurrent()

            viewModel.onAutoFrameCaptured("new-page".toByteArray())
            runCurrent()
            restoreMayFinish.complete(Unit)
            advanceUntilIdle()
            viewModel.onAutoFrameCaptured("new-page".toByteArray())
            advanceUntilIdle()

            assertContentEquals(listOf("new-page"), normalizer.received)
            assertTrue(viewModel.state.value.duplicateSkipped)
        }

    @Test
    fun `pressing the shutter twice on one page stores both`() =
        runTest {
            // Deliberate: the shutter is an instruction. Only a capture the app
            // decided to make on its own may be second-guessed.
            val normalizer = RecordingNormalizer()
            val viewModel = viewModel(normalizer).apply { signatureOf = { signatureOfName(it) } }
            advanceUntilIdle()

            viewModel.onFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()
            viewModel.onFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()

            assertContentEquals(listOf("page-200", "page-200"), normalizer.received)
            assertTrue(!viewModel.state.value.duplicateSkipped)
            assertEquals(2, viewModel.state.value.captureCount)
        }

    @Test
    fun `turning the page is not mistaken for a repeat`() =
        runTest {
            val normalizer = RecordingNormalizer()
            val viewModel = viewModel(normalizer).apply { signatureOf = { signatureOfName(it) } }
            advanceUntilIdle()

            viewModel.onAutoFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()
            viewModel.onAutoFrameCaptured("page-201".toByteArray())
            advanceUntilIdle()

            assertContentEquals(listOf("page-200", "page-201"), normalizer.received)
            assertEquals(2, viewModel.state.value.captureCount)
        }

    @Test
    fun `going back to a page after another one is stored again`() =
        runTest {
            // Only the immediately preceding page is compared. Photographing a
            // page again after moving on is a re-shoot the user meant, and a
            // book legitimately repeats near-identical pages far apart.
            val normalizer = RecordingNormalizer()
            val viewModel = viewModel(normalizer).apply { signatureOf = { signatureOfName(it) } }
            advanceUntilIdle()

            listOf("page-200", "page-201", "page-200").forEach {
                viewModel.onAutoFrameCaptured(it.toByteArray())
                advanceUntilIdle()
            }

            assertContentEquals(listOf("page-200", "page-201", "page-200"), normalizer.received)
        }

    @Test
    fun `without a fingerprint every automatic capture is kept`() =
        runTest {
            // The decoder can fail on a frame. Losing a page the user wanted is
            // the worse error, so an unusable fingerprint means "keep it".
            val normalizer = RecordingNormalizer()
            val viewModel = viewModel(normalizer)
            advanceUntilIdle()

            viewModel.onAutoFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()
            viewModel.onAutoFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()

            assertContentEquals(listOf("page-200", "page-200"), normalizer.received)
        }

    @Test
    fun `a skip notice is cleared once shown`() =
        runTest {
            val viewModel =
                viewModel(RecordingNormalizer()).apply { signatureOf = { signatureOfName(it) } }
            advanceUntilIdle()

            viewModel.onAutoFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()
            viewModel.onAutoFrameCaptured("page-200".toByteArray())
            advanceUntilIdle()
            viewModel.consumeDuplicateSkipped()

            assertTrue(!viewModel.state.value.duplicateSkipped, "a notice that never clears would repeat forever")
        }

    @Test
    fun `a failed capture does not count`() =
        runTest {
            // captureCount drives the flash and the vibration; flashing for a
            // page that was not stored would teach the user to trust it wrongly.
            val normalizer = RecordingNormalizer(failOn = setOf("broken"))
            val viewModel = viewModel(normalizer)
            advanceUntilIdle()

            viewModel.onFrameCaptured("broken".toByteArray())
            advanceUntilIdle()

            assertEquals(0, viewModel.state.value.captureCount)
            assertNotNull(viewModel.state.value.errorMessage)
        }
}
