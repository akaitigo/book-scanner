package dev.bookscanner.core.contracts

/**
 * Durable storage of scan sessions. Implementations must guarantee that a
 * process death at any point leaves every session readable (atomic metadata
 * commits) and must never mutate original page image files.
 */
interface ScanRepository : PageStore {
    suspend fun createSession(title: String): ScanSession

    /** All sessions, most recently updated first. */
    suspend fun listSessions(): List<ScanSession>

    /** Null if the session does not exist. */
    suspend fun getSession(id: SessionId): ScanSession?

    /** Deletes the session and all of its page files. Idempotent. */
    suspend fun deleteSession(id: SessionId)

    suspend fun renameSession(
        id: SessionId,
        title: String,
    ): ScanSession

    /** [orderedIds] must be a permutation of the session's current page ids. */
    suspend fun reorderPages(
        id: SessionId,
        orderedIds: List<PageId>,
    ): ScanSession

    /** Removes pages and deletes their image files. Unknown ids are ignored. */
    suspend fun removePages(
        id: SessionId,
        pageIds: Set<PageId>,
    ): ScanSession

    suspend fun updatePageGeometry(
        id: SessionId,
        pageId: PageId,
        geometry: PageGeometry,
    ): ScanSession
}
