package dev.bookscanner.core.session

import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.core.contracts.PageId
import dev.bookscanner.core.contracts.ScanRepository
import dev.bookscanner.core.contracts.ScanSession
import dev.bookscanner.core.contracts.ScannedPage
import dev.bookscanner.core.contracts.SessionId
import dev.bookscanner.core.contracts.StagedPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Filesystem-backed [ScanRepository] (ADR-0005).
 *
 * Layout:
 * ```
 * <root>/<sessionId>/manifest.json
 * <root>/<sessionId>/pages/<pageId>.jpg
 * ```
 *
 * Originals under `pages/` are written once and never mutated; all edits live
 * in the manifest as geometry. Manifest writes are atomic (temp + rename), so
 * a crash at any point leaves a readable session.
 *
 * All mutations are serialized through [mutex]: manifest updates are
 * read-modify-write, and concurrent captures would otherwise interleave and
 * lose pages.
 */
class FileScanRepository(
    private val root: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : ScanRepository {
    private val mutex = Mutex()

    override suspend fun createSession(title: String): ScanSession =
        mutate {
            val now = clock()
            val session =
                ScanSession(
                    id = SessionId(idFactory()),
                    title = title,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                )
            pagesDir(session.id).mkdirs()
            writeManifest(session)
            session
        }

    override suspend fun listSessions(): List<ScanSession> =
        withContext(ioDispatcher) {
            mutex.withLock {
                (root.listFiles { file -> file.isDirectory } ?: emptyArray())
                    .mapNotNull { dir -> loadSession(SessionId(dir.name)) }
                    .sortedByDescending { it.updatedAtEpochMs }
            }
        }

    override suspend fun getSession(id: SessionId): ScanSession? =
        withContext(ioDispatcher) {
            mutex.withLock { loadSession(id) }
        }

    override suspend fun deleteSession(id: SessionId) {
        withContext(ioDispatcher) {
            mutex.withLock { sessionDir(id).deleteRecursively() }
        }
    }

    override suspend fun renameSession(
        id: SessionId,
        title: String,
    ): ScanSession = update(id) { it.copy(title = title) }

    override suspend fun reorderPages(
        id: SessionId,
        orderedIds: List<PageId>,
    ): ScanSession = update(id) { it.withPageOrder(orderedIds) }

    override suspend fun removePages(
        id: SessionId,
        pageIds: Set<PageId>,
    ): ScanSession =
        mutate {
            val current = requireNotNull(loadSession(id)) { "Unknown session $id" }
            val removed = current.pages.filter { it.id in pageIds }
            val updated = current.withoutPages(pageIds).copy(updatedAtEpochMs = clock(), recovered = false)
            // Manifest first: if the image files were deleted first and the write
            // then failed, the session would reference missing pages. In the other
            // order the worst case is an orphaned file, which recovery ignores.
            writeManifest(updated)
            removed.forEach { File(pagesDir(id), it.fileName).delete() }
            updated
        }

    override suspend fun updatePageGeometry(
        id: SessionId,
        pageId: PageId,
        geometry: PageGeometry,
    ): ScanSession = update(id) { it.withPageGeometry(pageId, geometry) }

    // ---- PageStore ----

    override suspend fun stagePage(sessionId: SessionId): StagedPage =
        withContext(ioDispatcher) {
            mutex.withLock {
                requireNotNull(loadSession(sessionId)) { "Unknown session $sessionId" }
                val pageId = PageId(idFactory())
                val dir = pagesDir(sessionId)
                if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                    throw IOException("Cannot create page directory: $dir")
                }
                StagedPage(sessionId, pageId, File(dir, "${pageId.value}.jpg"))
            }
        }

    override suspend fun commitPage(
        staged: StagedPage,
        geometry: PageGeometry,
    ): ScannedPage {
        val file = staged.file
        // A staged file that is missing or empty means the writer failed or
        // was killed mid-write; committing it would put an unreadable page
        // into document order.
        if (!file.isFile || file.length() == 0L) {
            throw IOException("Staged page has no content: $file")
        }
        val page =
            ScannedPage(
                id = staged.pageId,
                fileName = file.name,
                createdAtEpochMs = clock(),
                geometry = geometry,
            )
        update(staged.sessionId) { session ->
            session.copy(pages = session.pages + page)
        }
        return page
    }

    override suspend fun discardPage(staged: StagedPage) {
        withContext(ioDispatcher) { staged.file.delete() }
    }

    override fun pageFile(
        sessionId: SessionId,
        page: ScannedPage,
    ): File = File(pagesDir(sessionId), page.fileName)

    // ---- internals ----

    private fun sessionDir(id: SessionId) = File(root, id.value)

    private fun pagesDir(id: SessionId) = File(sessionDir(id), PAGES_DIR)

    private fun manifestFile(id: SessionId) = File(sessionDir(id), MANIFEST_NAME)

    private suspend fun <T> mutate(block: () -> T): T = withContext(ioDispatcher) { mutex.withLock { block() } }

    private suspend fun update(
        id: SessionId,
        transform: (ScanSession) -> ScanSession,
    ): ScanSession =
        mutate {
            val current = requireNotNull(loadSession(id)) { "Unknown session $id" }
            val updated = transform(current).copy(updatedAtEpochMs = clock(), recovered = false)
            writeManifest(updated)
            updated
        }

    private fun writeManifest(session: ScanSession) {
        val bytes = manifestJson.encodeToString(session.toManifest()).toByteArray()
        writeAtomically(manifestFile(session.id), bytes)
    }

    /**
     * Reads a session, falling back to recovery when the manifest is missing,
     * truncated, or otherwise unparseable. Recovery re-indexes the page files
     * present on disk (ordered by last-modified, the best available proxy for
     * capture order) and flags the session so the UI can ask the user to
     * verify page order.
     */
    private fun loadSession(id: SessionId): ScanSession? {
        val dir = sessionDir(id)
        if (!dir.isDirectory) return null
        val manifest = manifestFile(id)
        val parsed =
            if (manifest.isFile) {
                runCatching { manifestJson.decodeFromString<SessionManifest>(manifest.readText()) }
                    .getOrNull()
            } else {
                null
            }
        val session = parsed?.toSession()
        if (session != null) return session.reconcileWithDisk(id)

        // Only attempt recovery for a directory that actually holds page data.
        // Without this, any stray directory under root — including one left
        // half-created by an interrupted createSession — would surface in the
        // session list as an empty "Recovered session" the user never made.
        if (pageFileNames(id).isEmpty()) return null
        return recoverSession(id)
    }

    /**
     * Drops manifest entries whose image file has vanished. Without this, a
     * missing file would surface as a broken thumbnail and, worse, break PDF
     * export at the very end of a long job.
     */
    private fun ScanSession.reconcileWithDisk(id: SessionId): ScanSession {
        val existing = pageFileNames(id)
        val kept = pages.filter { it.fileName in existing }
        return if (kept.size == pages.size) this else copy(pages = kept, recovered = true)
    }

    private fun recoverSession(id: SessionId): ScanSession {
        val files =
            (pagesDir(id).listFiles { file -> file.isFile } ?: emptyArray())
                .sortedBy { it.lastModified() }
        val now = clock()
        return ScanSession(
            id = id,
            title = "Recovered session",
            createdAtEpochMs = files.minOfOrNull { it.lastModified() } ?: now,
            updatedAtEpochMs = now,
            pages =
                files.map { file ->
                    ScannedPage(
                        id = PageId(file.nameWithoutExtension),
                        fileName = file.name,
                        createdAtEpochMs = file.lastModified(),
                    )
                },
            recovered = true,
        )
    }

    private fun pageFileNames(id: SessionId): Set<String> =
        (pagesDir(id).listFiles { file -> file.isFile } ?: emptyArray())
            .map { it.name }
            .toSet()

    private companion object {
        const val MANIFEST_NAME = "manifest.json"
        const val PAGES_DIR = "pages"
    }
}
