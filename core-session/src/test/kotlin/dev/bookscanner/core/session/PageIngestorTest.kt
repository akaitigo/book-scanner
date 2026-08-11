package dev.bookscanner.core.session

import dev.bookscanner.core.contracts.EngineId
import dev.bookscanner.core.contracts.NormalizedPage
import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.core.contracts.PageImageNormalizer
import dev.bookscanner.core.contracts.SessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PageIngestorTest {
    private lateinit var root: File
    private val ids = AtomicLong(0)

    private fun newRepository() =
        FileScanRepository(
            root = root,
            ioDispatcher = Dispatchers.Unconfined,
            clock = { 1_000 },
            idFactory = { "id-${ids.incrementAndGet()}" },
        )

    /** Copies input through and reports whatever geometry the test asks for. */
    private class FakeNormalizer(
        private val geometry: PageGeometry = PageGeometry.IDENTITY,
        private val failWith: Throwable? = null,
        private val writeNothing: Boolean = false,
    ) : PageImageNormalizer {
        override val engine = EngineId.PRODUCTION

        override suspend fun normalize(
            input: InputStream,
            output: OutputStream,
        ): NormalizedPage {
            failWith?.let { throw it }
            if (!writeNothing) output.write(input.readBytes())
            return NormalizedPage(geometry = geometry, losslessCopy = true)
        }
    }

    @Before
    fun setUp() {
        root =
            File.createTempFile("ingestor-test", "").let {
                it.delete()
                it.mkdirs()
                it
            }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun pagesDir(id: SessionId) = File(root, "${id.value}/pages")

    @Test
    fun `ingested pages land in capture order`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            val ingestor = PageIngestor(repo, FakeNormalizer())

            ingestor.ingest(session.id, "one".toByteArray())
            ingestor.ingest(session.id, "two".toByteArray())
            ingestor.ingest(session.id, "three".toByteArray())

            val pages = requireNotNull(repo.getSession(session.id)).pages
            assertEquals(3, pages.size)
            assertEquals(
                listOf("one", "two", "three"),
                pages.map { repo.pageFile(session.id, it).readText() },
            )
        }

    @Test
    fun `normalizer geometry is stored with the page`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            val geometry = PageGeometry(rotationDegrees = 270)
            val ingestor = PageIngestor(repo, FakeNormalizer(geometry = geometry))

            val page = ingestor.ingest(session.id, "bytes".toByteArray())

            val reloaded = requireNotNull(newRepository().getSession(session.id))
            assertEquals(geometry, requireNotNull(reloaded.page(page.id)).geometry)
        }

    @Test
    fun `a failing normalizer leaves no page and no file behind`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            val ingestor = PageIngestor(repo, FakeNormalizer(failWith = IOException("bad image")))

            assertFailsWith<IOException> { ingestor.ingest(session.id, "junk".toByteArray()) }

            assertEquals(0, requireNotNull(repo.getSession(session.id)).pageCount)
            assertTrue(
                pagesDir(session.id).listFiles().orEmpty().isEmpty(),
                "the staged file must be cleaned up",
            )
        }

    @Test
    fun `a normalizer that writes nothing does not create an empty page`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            val ingestor = PageIngestor(repo, FakeNormalizer(writeNothing = true))

            assertFailsWith<IOException> { ingestor.ingest(session.id, "bytes".toByteArray()) }

            assertEquals(0, requireNotNull(repo.getSession(session.id)).pageCount)
            assertTrue(pagesDir(session.id).listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `ingesting into an unknown session fails`() =
        runTest {
            val repo = newRepository()
            val ingestor = PageIngestor(repo, FakeNormalizer())

            assertFailsWith<IllegalArgumentException> {
                ingestor.ingest(SessionId("nope"), "bytes".toByteArray())
            }
        }

    @Test
    fun `book scale ingest keeps every page`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Big book")
            val ingestor = PageIngestor(repo, FakeNormalizer())

            repeat(120) { index -> ingestor.ingest(session.id, "page-$index".toByteArray()) }

            val reloaded = requireNotNull(newRepository().getSession(session.id))
            assertEquals(120, reloaded.pageCount)
            assertEquals(
                (0 until 120).map { "page-$it" },
                reloaded.pages.map { repo.pageFile(session.id, it).readText() },
            )
        }
}
