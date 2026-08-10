package dev.bookscanner.core.session

import dev.bookscanner.core.contracts.SessionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recovery must not invent sessions. A stray directory under the storage root
 * has no page data and no manifest, so it is not a session — surfacing it
 * would put phantom entries in the user's list.
 */
class FileScanRepositoryRecoveryTest {
    private lateinit var root: File
    private val ids = AtomicLong(0)

    private fun newRepository() =
        FileScanRepository(
            root = root,
            ioDispatcher = Dispatchers.Unconfined,
            clock = { 1_000 },
            idFactory = { "id-${ids.incrementAndGet()}" },
        )

    @Before
    fun setUp() {
        root =
            File.createTempFile("recovery-test", "").let {
                it.delete()
                it.mkdirs()
                it
            }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `directory with neither manifest nor pages is not a session`() =
        runTest {
            File(root, "stray-directory").mkdirs()

            assertNull(newRepository().getSession(SessionId("stray-directory")))
            assertTrue(newRepository().listSessions().isEmpty())
        }

    @Test
    fun `directory with an empty pages folder is not a session`() =
        runTest {
            File(root, "half-created/pages").mkdirs()

            assertTrue(newRepository().listSessions().isEmpty())
        }

    @Test
    fun `directory with page files but no manifest is recovered`() =
        runTest {
            val pages = File(root, "orphaned/pages")
            pages.mkdirs()
            File(pages, "p1.jpg").writeText("one")
            File(pages, "p2.jpg").writeText("two")

            val sessions = newRepository().listSessions()

            assertEquals(1, sessions.size)
            assertTrue(sessions.first().recovered)
            assertEquals(2, sessions.first().pageCount)
        }

    @Test
    fun `real sessions are unaffected by a stray directory alongside them`() =
        runTest {
            val repo = newRepository()
            val session = repo.createSession("Real book")
            File(root, "junk").mkdirs()

            val sessions = repo.listSessions()

            assertEquals(listOf(session.id), sessions.map { it.id })
        }
}
