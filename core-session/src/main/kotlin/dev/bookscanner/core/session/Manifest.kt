package dev.bookscanner.core.session

import dev.bookscanner.core.contracts.CropRect
import dev.bookscanner.core.contracts.NormalizedPoint
import dev.bookscanner.core.contracts.PageBoundary
import dev.bookscanner.core.contracts.PageGeometry
import dev.bookscanner.core.contracts.PageId
import dev.bookscanner.core.contracts.ScanSession
import dev.bookscanner.core.contracts.ScannedPage
import dev.bookscanner.core.contracts.SessionId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-disk representation of a session (ADR-0005). Kept separate from the
 * domain model so the storage format can evolve without forcing the domain
 * to carry serialization concerns.
 *
 * [version] exists so a future format change can be detected rather than
 * silently misread. Unknown fields are ignored on read (forward compat).
 */
@Serializable
internal data class SessionManifest(
    val version: Int = CURRENT_VERSION,
    val id: String,
    val title: String,
    @SerialName("createdAt") val createdAtEpochMs: Long,
    @SerialName("updatedAt") val updatedAtEpochMs: Long,
    val pages: List<PageEntry> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Serializable
internal data class PageEntry(
    val id: String,
    val file: String,
    @SerialName("createdAt") val createdAtEpochMs: Long,
    val rotation: Int = 0,
    val crop: CropEntry? = null,
    /** Perspective quadrilateral, clockwise from top-left, normalized. */
    val boundary: List<PointEntry>? = null,
)

@Serializable
internal data class PointEntry(
    val x: Float,
    val y: Float,
)

@Serializable
internal data class CropEntry(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal val manifestJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

internal fun ScanSession.toManifest(): SessionManifest =
    SessionManifest(
        id = id.value,
        title = title,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        pages =
            pages.map { page ->
                PageEntry(
                    id = page.id.value,
                    file = page.fileName,
                    createdAtEpochMs = page.createdAtEpochMs,
                    rotation = page.geometry.rotationDegrees,
                    crop = page.geometry.crop?.let { CropEntry(it.left, it.top, it.right, it.bottom) },
                    boundary =
                        page.geometry.boundary
                            ?.corners
                            ?.map { PointEntry(it.x, it.y) },
                )
            },
    )

/**
 * Converts a manifest back into a domain session.
 *
 * Individual page entries carrying values the domain rejects (an invalid
 * rotation, a degenerate crop) are not allowed to take down the whole
 * session: geometry falls back to identity, which is always recoverable by
 * the user. A structurally unreadable manifest is a separate case handled by
 * [FileScanRepository] recovery.
 */
internal fun SessionManifest.toSession(): ScanSession =
    ScanSession(
        id = SessionId(id),
        title = title,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        pages =
            pages
                .distinctBy { it.id }
                .map { entry ->
                    ScannedPage(
                        id = PageId(entry.id),
                        fileName = entry.file,
                        createdAtEpochMs = entry.createdAtEpochMs,
                        geometry = entry.toGeometry(),
                    )
                },
    )

private fun PageEntry.toGeometry(): PageGeometry =
    runCatching {
        PageGeometry(
            rotationDegrees = rotation,
            crop = crop?.let { CropRect(it.left, it.top, it.right, it.bottom) },
            boundary = boundary?.toBoundary(),
        )
    }.getOrElse { PageGeometry.IDENTITY }

/**
 * Rebuilds a boundary from its four stored corners. Anything other than four
 * points is corrupt; the page keeps its pixels and loses only the correction,
 * which the user can redo.
 */
private fun List<PointEntry>.toBoundary(): PageBoundary? {
    if (size != 4) return null
    val points = map { NormalizedPoint(it.x, it.y) }
    return PageBoundary(points[0], points[1], points[2], points[3])
}
