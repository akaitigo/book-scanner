package dev.bookscanner.core.contracts

/**
 * A single captured or imported page. [fileName] is relative to the session's
 * page directory; resolution to an absolute path is the repository's concern.
 */
data class ScannedPage(
    val id: PageId,
    val fileName: String,
    val createdAtEpochMs: Long,
    val geometry: PageGeometry = PageGeometry.IDENTITY,
)

/**
 * An ordered scanning session for one book (or part of one).
 * Page order in [pages] IS the document order.
 *
 * [recovered] marks a session whose manifest was lost/corrupt and whose page
 * list was re-indexed from files on disk; order may need user review.
 */
data class ScanSession(
    val id: SessionId,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val pages: List<ScannedPage> = emptyList(),
    val recovered: Boolean = false,
) {
    init {
        val ids = pages.map { it.id }
        require(ids.size == ids.toSet().size) { "Duplicate page ids in session $id" }
    }

    val pageCount: Int get() = pages.size

    fun page(pageId: PageId): ScannedPage? = pages.firstOrNull { it.id == pageId }

    /**
     * Reorder pages to [orderedIds], which must be a permutation of the
     * current page ids.
     */
    fun withPageOrder(orderedIds: List<PageId>): ScanSession {
        require(orderedIds.toSet() == pages.map { it.id }.toSet() && orderedIds.size == pages.size) {
            "orderedIds must be a permutation of the current page ids"
        }
        val byId = pages.associateBy { it.id }
        return copy(pages = orderedIds.map { byId.getValue(it) })
    }

    fun withoutPages(pageIds: Set<PageId>): ScanSession = copy(pages = pages.filterNot { it.id in pageIds })

    fun withPageGeometry(
        pageId: PageId,
        geometry: PageGeometry,
    ): ScanSession {
        require(pages.any { it.id == pageId }) { "Unknown page $pageId in session $id" }
        return copy(pages = pages.map { if (it.id == pageId) it.copy(geometry = geometry) else it })
    }
}
