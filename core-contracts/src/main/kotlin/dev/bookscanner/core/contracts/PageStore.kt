package dev.bookscanner.core.contracts

import java.io.File

/**
 * A page image reserved on disk but not yet part of the session. The capture
 * or import code writes JPEG bytes into [file], then either commits the page
 * (it enters the manifest and document order) or discards it. A crash between
 * staging and commit loses only this file, never session integrity.
 */
data class StagedPage(
    val sessionId: SessionId,
    val pageId: PageId,
    val file: File,
)

/**
 * Storage of page image files. Split from [ScanRepository] so capture UI can
 * depend on the narrow surface it needs.
 */
interface PageStore {
    /** Reserve a new page slot and target file for writing. */
    suspend fun stagePage(sessionId: SessionId): StagedPage

    /**
     * Commit a staged page (whose [StagedPage.file] must now contain a
     * complete JPEG) to the end of the session's page list.
     *
     * [geometry] carries any orientation the source declared, which
     * normalization moves out of the pixels and into page state — see
     * [PageImageNormalizer].
     */
    suspend fun commitPage(
        staged: StagedPage,
        geometry: PageGeometry = PageGeometry.IDENTITY,
    ): ScannedPage

    /** Delete a staged page that will not be committed. */
    suspend fun discardPage(staged: StagedPage)

    /** Absolute file of a committed page's original image. */
    fun pageFile(
        sessionId: SessionId,
        page: ScannedPage,
    ): File
}
