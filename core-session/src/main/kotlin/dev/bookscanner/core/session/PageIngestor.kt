package dev.bookscanner.core.session

import dev.bookscanner.core.contracts.PageImageNormalizer
import dev.bookscanner.core.contracts.PageStore
import dev.bookscanner.core.contracts.ScannedPage
import dev.bookscanner.core.contracts.SessionId
import java.io.InputStream

/**
 * The single write boundary for page images: normalize, then commit.
 *
 * Every page — camera capture or picker import — enters a session through
 * here, which is what makes the storage invariant in [PageImageNormalizer]
 * hold for the whole repository rather than only for the paths someone
 * remembered to route through it.
 *
 * Failure semantics: a page that cannot be normalized is discarded, not
 * committed. A half-written or undecodable file must never enter document
 * order, because it would surface much later as a broken export.
 */
class PageIngestor(
    private val store: PageStore,
    private val normalizer: PageImageNormalizer,
) {
    /**
     * Reads [input] fully and appends it to [sessionId] as the next page.
     * The caller owns closing [input].
     */
    suspend fun ingest(
        sessionId: SessionId,
        input: InputStream,
    ): ScannedPage {
        val staged = store.stagePage(sessionId)
        val normalized =
            try {
                staged.file.outputStream().use { output -> normalizer.normalize(input, output) }
            } catch (error: Throwable) {
                store.discardPage(staged)
                throw error
            }
        return try {
            store.commitPage(staged, normalized.geometry)
        } catch (error: Throwable) {
            store.discardPage(staged)
            throw error
        }
    }

    suspend fun ingest(
        sessionId: SessionId,
        bytes: ByteArray,
    ): ScannedPage = ingest(sessionId, bytes.inputStream())
}
