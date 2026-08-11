package dev.bookscanner.core.session

import dev.bookscanner.core.contracts.CropRect
import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.core.contracts.PageId
import dev.bookscanner.core.contracts.ScanSession
import dev.bookscanner.core.contracts.SessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileScanRepositoryTest {
    private lateinit var root: File
    private val ids = AtomicLong(0)
    private val now = AtomicLong(1_000)

    private fun newRepository() =
        FileScanRepository(
            root = root,
            ioDispatcher = Dispatchers.Unconfined,
            clock = { now.get() },
            idFactory = { "id-${ids.incrementAndGet()}" },
        )

    @Before
    fun setUp() {
        root =
            File.createTempFile("book-scanner-test", "").let { file ->
                file.delete()
                file.mkdirs()
                file
            }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** Writes JPEG-ish bytes into a staged page and commits it. */
    private suspend fun FileScanRepository.addPage(
        sessionId: SessionId,
        content: String = "page-bytes",
    ): PageId {
        val staged = stagePage(sessionId)
        staged.file.writeText(content)
        return commitPage(staged).id
    }

    @Test
    fun `create list and reopen session`() =
        runTest {
            val repo = newRepository()
            val created = repo.createSession("Book A")

            assertEquals("Book A", created.title)
            assertEquals(0, created.pageCount)
            assertEquals(listOf(created.id), repo.listSessions().map { it.id })

            // A fresh repository instance proves the state came off disk, not memory.
            val reopened = newRepository().getSession(created.id)
            assertEquals(created.title, reopened?.title)
            assertEquals(created.id, reopened?.id)
        }

    @Test
    fun `getSession returns null for unknown id`() =
        runTest {
            assertNull(newRepository().getSession(SessionId("nope")))
        }

    @Test
    fun `pages persist in capture order across repository instances`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            val a = repo.addPage(session.id, "a")
            val b = repo.addPage(session.id, "b")
            val c = repo.addPage(session.id, "c")

            val reloaded = requireNotNull(newRepository().getSession(session.id))
            assertContentEquals(listOf(a, b, c), reloaded.pages.map { it.id })
            assertFalse(reloaded.recovered)
        }

    @Test
    fun `reorder persists`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            val a = repo.addPage(session.id)
            val b = repo.addPage(session.id)
            val c = repo.addPage(session.id)

            repo.reorderPages(session.id, listOf(c, a, b))

            val reloaded = requireNotNull(newRepository().getSession(session.id))
            assertContentEquals(listOf(c, a, b), reloaded.pages.map { it.id })
        }

    @Test
    fun `remove deletes only the targeted pages and their files`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            val a = repo.addPage(session.id, "a")
            val b = repo.addPage(session.id, "b")
            val c = repo.addPage(session.id, "c")

            val fileB = File(root, "${session.id.value}/pages/${b.value}.jpg")
            assertTrue(fileB.isFile)

            val updated = repo.removePages(session.id, setOf(b))
            assertContentEquals(listOf(a, c), updated.pages.map { it.id })
            assertFalse(fileB.exists(), "removed page file should be deleted")
            assertTrue(File(root, "${session.id.value}/pages/${a.value}.jpg").isFile)
            assertTrue(File(root, "${session.id.value}/pages/${c.value}.jpg").isFile)

            assertContentEquals(
                listOf(a, c),
                requireNotNull(newRepository().getSession(session.id)).pages.map { it.id },
            )
        }

    @Test
    fun `removing unknown page ids is a no-op`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            val a = repo.addPage(session.id)

            val updated = repo.removePages(session.id, setOf(PageId("ghost")))
            assertContentEquals(listOf(a), updated.pages.map { it.id })
        }

    @Test
    fun `geometry persists and originals are untouched`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            val pageId = repo.addPage(session.id, "original-bytes")
            val geometry = PageGeometry(rotationDegrees = 270, crop = CropRect(0.1f, 0.2f, 0.8f, 0.9f))

            repo.updatePageGeometry(session.id, pageId, geometry)

            val reloaded = requireNotNull(newRepository().getSession(session.id))
            assertEquals(geometry, requireNotNull(reloaded.page(pageId)).geometry)
            // Non-destructive editing: the captured file must be byte-identical.
            assertEquals("original-bytes", File(root, "${session.id.value}/pages/${pageId.value}.jpg").readText())
        }

    @Test
    fun `rename persists`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Untitled")
            repo.renameSession(session.id, "Kotlin in Action")
            assertEquals("Kotlin in Action", newRepository().getSession(session.id)?.title)
        }

    @Test
    fun `delete session removes directory and pages`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            repo.addPage(session.id)

            repo.deleteSession(session.id)

            assertNull(repo.getSession(session.id))
            assertFalse(File(root, session.id.value).exists())
            // Idempotent.
            repo.deleteSession(session.id)
        }

    @Test
    fun `staged page is invisible until committed and discard removes it`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")

            val staged = repo.stagePage(session.id)
            staged.file.writeText("bytes")
            assertEquals(0, requireNotNull(repo.getSession(session.id)).pageCount)

            repo.discardPage(staged)
            assertFalse(staged.file.exists())
            assertEquals(0, requireNotNull(repo.getSession(session.id)).pageCount)
        }

    @Test
    fun `committing an empty staged page fails rather than corrupting order`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            val staged = repo.stagePage(session.id)
            // File never written: simulates a capture killed mid-write.

            val error = runCatching { repo.commitPage(staged) }.exceptionOrNull()
            assertTrue(error is java.io.IOException, "expected IOException, got $error")
            assertEquals(0, requireNotNull(repo.getSession(session.id)).pageCount)
        }

    @Test
    fun `sessions are listed most recently updated first`() =
        runTest {
            val repo = newRepository()
            now.set(1_000)
            val first = repo.createSession("First")
            now.set(2_000)
            val second = repo.createSession("Second")
            now.set(3_000)
            repo.renameSession(first.id, "First (edited)")

            assertContentEquals(listOf(first.id, second.id), repo.listSessions().map { it.id })
        }

    // ---- recovery ----

    @Test
    fun `corrupt manifest recovers pages from disk`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            repo.addPage(session.id, "a")
            repo.addPage(session.id, "b")

            File(root, "${session.id.value}/manifest.json").writeText("{ this is not json")

            val recovered = requireNotNull(newRepository().getSession(session.id))
            assertTrue(recovered.recovered)
            assertEquals(2, recovered.pageCount)
        }

    @Test
    fun `missing manifest recovers pages from disk`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            repo.addPage(session.id, "a")

            assertTrue(File(root, "${session.id.value}/manifest.json").delete())

            val recovered = requireNotNull(newRepository().getSession(session.id))
            assertTrue(recovered.recovered)
            assertEquals(1, recovered.pageCount)
        }

    @Test
    fun `next write after recovery clears the recovered flag`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            repo.addPage(session.id, "a")
            File(root, "${session.id.value}/manifest.json").writeText("<<truncated")

            val repo2 = newRepository()
            assertTrue(requireNotNull(repo2.getSession(session.id)).recovered)

            repo2.renameSession(session.id, "Repaired")
            val after = requireNotNull(newRepository().getSession(session.id))
            assertFalse(after.recovered)
            assertEquals("Repaired", after.title)
        }

    @Test
    fun `manifest entry whose file vanished is dropped`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            val a = repo.addPage(session.id, "a")
            val b = repo.addPage(session.id, "b")

            assertTrue(File(root, "${session.id.value}/pages/${a.value}.jpg").delete())

            val reloaded = requireNotNull(newRepository().getSession(session.id))
            assertContentEquals(listOf(b), reloaded.pages.map { it.id })
            assertTrue(reloaded.recovered, "session should be flagged so the UI can warn")
        }

    @Test
    fun `unknown manifest fields are ignored`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            val pageId = repo.addPage(session.id, "a")

            val manifest = File(root, "${session.id.value}/manifest.json")
            val patched =
                manifest.readText().replaceFirst(
                    "\"pages\"",
                    "\"futureFeature\": { \"ocr\": true },\n  \"pages\"",
                )
            manifest.writeText(patched)

            val reloaded = requireNotNull(newRepository().getSession(session.id))
            assertFalse(reloaded.recovered)
            assertContentEquals(listOf(pageId), reloaded.pages.map { it.id })
        }

    @Test
    fun `invalid geometry in manifest falls back to identity without losing the page`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Book")
            val pageId = repo.addPage(session.id, "a")

            val manifest = File(root, "${session.id.value}/manifest.json")
            manifest.writeText(manifest.readText().replace("\"rotation\": 0", "\"rotation\": 37"))

            val reloaded = requireNotNull(newRepository().getSession(session.id))
            assertContentEquals(listOf(pageId), reloaded.pages.map { it.id })
            assertEquals(PageGeometry.IDENTITY, requireNotNull(reloaded.page(pageId)).geometry)
        }

    // ---- scale ----

    @Test
    fun `book scale manifest round trips quickly`() =
        runTest {
            val pages =
                (1..500).map { index ->
                    dev.bookscanner.core.contracts.ScannedPage(
                        id = PageId("page-$index"),
                        fileName = "page-$index.jpg",
                        createdAtEpochMs = index.toLong(),
                        geometry = PageGeometry(rotationDegrees = 90, crop = CropRect(0f, 0f, 0.9f, 0.9f)),
                    )
                }
            val session =
                ScanSession(
                    id = SessionId("big"),
                    title = "Big book",
                    createdAtEpochMs = 0,
                    updatedAtEpochMs = 0,
                    pages = pages,
                )

            val encoded = manifestJson.encodeToString(session.toManifest())

            // Best of several runs, after a warm-up. A single cold measurement
            // here was measuring JIT compilation and CPU contention from the
            // rest of a parallel build, not the parse: it drifted past 200 ms
            // while the code was untouched. The guard is against a manifest
            // format that scales badly, so the achievable cost is the honest
            // figure.
            repeat(3) { manifestJson.decodeFromString<SessionManifest>(encoded) }
            var bestMs = Long.MAX_VALUE
            var decoded: ScanSession? = null
            repeat(5) {
                val start = System.nanoTime()
                decoded = manifestJson.decodeFromString<SessionManifest>(encoded).toSession()
                bestMs = minOf(bestMs, (System.nanoTime() - start) / 1_000_000)
            }

            val result = requireNotNull(decoded)
            println("MEASURE manifest-parse pages=500 bestMs=$bestMs")
            assertEquals(500, result.pageCount)
            assertContentEquals(pages.map { it.id }, result.pages.map { it.id })
            assertTrue(bestMs < 100, "500-page manifest parse took ${bestMs}ms at best")
        }
}
