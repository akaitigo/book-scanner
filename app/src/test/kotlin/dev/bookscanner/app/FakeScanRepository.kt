package dev.bookscanner.app

import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.core.contracts.PageId
import dev.bookscanner.core.contracts.ScanRepository
import dev.bookscanner.core.contracts.ScanSession
import dev.bookscanner.core.contracts.ScannedPage
import dev.bookscanner.core.contracts.SessionId
import dev.bookscanner.core.contracts.StagedPage
import java.io.File
import java.io.IOException

/**
 * In-memory [ScanRepository] for ViewModel tests.
 *
 * [failNext] makes a single operation fail, so error paths are exercised
 * deliberately rather than only when something is accidentally broken.
 */
class FakeScanRepository(
    // A real directory: PageIngestor writes staged bytes to disk, so a fake
    // that hands out unwritable paths would fail every ingest test for the
    // wrong reason.
    private val root: File = createTempRoot(),
) : ScanRepository {
    private val sessions = linkedMapOf<String, ScanSession>()
    private var nextId = 0
    private var clock = 1_000L

    var failNext: Throwable? = null
    var reorderCount = 0
        private set

    fun seed(
        title: String = "Book",
        pageCount: Int = 0,
    ): ScanSession {
        val id = SessionId("s${++nextId}")
        val session =
            ScanSession(
                id = id,
                title = title,
                createdAtEpochMs = clock,
                updatedAtEpochMs = clock++,
                pages =
                    (1..pageCount).map { index ->
                        ScannedPage(PageId("${id.value}-p$index"), "p$index.jpg", createdAtEpochMs = clock)
                    },
            )
        sessions[id.value] = session
        return session
    }

    fun current(id: SessionId): ScanSession = requireNotNull(sessions[id.value]) { "Unknown session $id" }

    private fun <T> guard(block: () -> T): T {
        failNext?.let {
            failNext = null
            throw it
        }
        return block()
    }

    private fun store(session: ScanSession): ScanSession {
        val updated = session.copy(updatedAtEpochMs = clock++)
        sessions[updated.id.value] = updated
        return updated
    }

    override suspend fun createSession(title: String): ScanSession =
        guard {
            val id = SessionId("s${++nextId}")
            store(ScanSession(id, title, clock, clock))
        }

    override suspend fun listSessions(): List<ScanSession> =
        guard {
            sessions.values.sortedByDescending { it.updatedAtEpochMs }
        }

    override suspend fun getSession(id: SessionId): ScanSession? = guard { sessions[id.value] }

    override suspend fun deleteSession(id: SessionId) {
        guard { sessions.remove(id.value) }
    }

    override suspend fun renameSession(
        id: SessionId,
        title: String,
    ): ScanSession = guard { store(current(id).copy(title = title)) }

    override suspend fun reorderPages(
        id: SessionId,
        orderedIds: List<PageId>,
    ): ScanSession =
        guard {
            reorderCount++
            store(current(id).withPageOrder(orderedIds))
        }

    override suspend fun removePages(
        id: SessionId,
        pageIds: Set<PageId>,
    ): ScanSession = guard { store(current(id).withoutPages(pageIds)) }

    override suspend fun updatePageGeometry(
        id: SessionId,
        pageId: PageId,
        geometry: PageGeometry,
    ): ScanSession = guard { store(current(id).withPageGeometry(pageId, geometry)) }

    override suspend fun stagePage(sessionId: SessionId): StagedPage =
        guard {
            require(sessions.containsKey(sessionId.value)) { "Unknown session $sessionId" }
            val pageId = PageId("${sessionId.value}-staged${++nextId}")
            root.mkdirs()
            StagedPage(sessionId, pageId, File(root, "${pageId.value}.jpg"))
        }

    override suspend fun commitPage(
        staged: StagedPage,
        geometry: PageGeometry,
    ): ScannedPage =
        guard {
            // Mirrors the real repository: an empty staged file means the write
            // failed, and must not enter document order.
            if (!staged.file.isFile || staged.file.length() == 0L) {
                throw IOException("Staged page has no content: " + staged.file)
            }
            val page =
                ScannedPage(staged.pageId, "${staged.pageId.value}.jpg", createdAtEpochMs = clock, geometry = geometry)
            val session = current(staged.sessionId)
            store(session.copy(pages = session.pages + page))
            page
        }

    override suspend fun discardPage(staged: StagedPage) {
        staged.file.delete()
    }

    override fun pageFile(
        sessionId: SessionId,
        page: ScannedPage,
    ): File = File(root, "${sessionId.value}/${page.fileName}")
}

class TestFailure(
    message: String = "boom",
) : IOException(message)

private fun createTempRoot(): File =
    File.createTempFile("fake-scan-repo", "").let {
        it.delete()
        it.mkdirs()
        it.deleteOnExit()
        it
    }
