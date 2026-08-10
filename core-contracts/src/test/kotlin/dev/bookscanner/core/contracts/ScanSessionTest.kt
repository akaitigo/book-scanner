package dev.bookscanner.core.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScanSessionTest {
    private fun page(id: String) = ScannedPage(PageId(id), "$id.jpg", createdAtEpochMs = 0)

    private fun session(vararg pageIds: String) =
        ScanSession(
            id = SessionId("s1"),
            title = "Test",
            createdAtEpochMs = 0,
            updatedAtEpochMs = 0,
            pages = pageIds.map { page(it) },
        )

    @Test
    fun `duplicate page ids are rejected`() {
        assertFailsWith<IllegalArgumentException> { session("a", "a") }
    }

    @Test
    fun `withPageOrder reorders pages`() {
        val reordered = session("a", "b", "c").withPageOrder(listOf(PageId("c"), PageId("a"), PageId("b")))
        assertEquals(listOf("c", "a", "b"), reordered.pages.map { it.id.value })
    }

    @Test
    fun `withPageOrder rejects non-permutations`() {
        val s = session("a", "b")
        assertFailsWith<IllegalArgumentException> { s.withPageOrder(listOf(PageId("a"))) }
        assertFailsWith<IllegalArgumentException> { s.withPageOrder(listOf(PageId("a"), PageId("x"))) }
        assertFailsWith<IllegalArgumentException> { s.withPageOrder(listOf(PageId("a"), PageId("a"))) }
    }

    @Test
    fun `withoutPages removes only requested pages and ignores unknown ids`() {
        val s = session("a", "b", "c").withoutPages(setOf(PageId("b"), PageId("x")))
        assertEquals(listOf("a", "c"), s.pages.map { it.id.value })
    }

    @Test
    fun `withPageGeometry updates only the target page`() {
        val geometry = PageGeometry(rotationDegrees = 90)
        val s = session("a", "b").withPageGeometry(PageId("b"), geometry)
        assertEquals(PageGeometry.IDENTITY, requireNotNull(s.page(PageId("a"))).geometry)
        assertEquals(geometry, requireNotNull(s.page(PageId("b"))).geometry)
    }

    @Test
    fun `withPageGeometry rejects unknown page`() {
        assertFailsWith<IllegalArgumentException> {
            session("a").withPageGeometry(PageId("x"), PageGeometry.IDENTITY)
        }
    }
}
