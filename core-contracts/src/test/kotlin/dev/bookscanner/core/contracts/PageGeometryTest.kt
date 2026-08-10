package dev.bookscanner.core.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageGeometryTest {
    @Test
    fun `four quarter turns return to identity`() {
        var g = PageGeometry.IDENTITY
        repeat(4) { g = g.rotatedBy(90) }
        assertEquals(PageGeometry.IDENTITY, g)
    }

    @Test
    fun `negative rotation normalizes into 0-270`() {
        assertEquals(270, PageGeometry.IDENTITY.rotatedBy(-90).rotationDegrees)
        assertEquals(180, PageGeometry.IDENTITY.rotatedBy(-180).rotationDegrees)
        assertEquals(0, PageGeometry.IDENTITY.rotatedBy(-360).rotationDegrees)
    }

    @Test
    fun `rotation over 360 normalizes`() {
        assertEquals(90, PageGeometry.IDENTITY.rotatedBy(450).rotationDegrees)
    }

    @Test
    fun `non multiple of 90 rotation is rejected`() {
        assertFailsWith<IllegalArgumentException> { PageGeometry.IDENTITY.rotatedBy(45) }
    }

    @Test
    fun `constructor rejects invalid rotation`() {
        assertFailsWith<IllegalArgumentException> { PageGeometry(rotationDegrees = 30) }
    }

    @Test
    fun `rotation preserves crop`() {
        val crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f)
        assertEquals(crop, PageGeometry(crop = crop).rotatedBy(90).crop)
    }

    @Test
    fun `isIdentity only for default state`() {
        assertTrue(PageGeometry.IDENTITY.isIdentity)
        assertTrue(!PageGeometry(rotationDegrees = 90).isIdentity)
        assertTrue(!PageGeometry(crop = CropRect.FULL).isIdentity)
    }
}

class CropRectTest {
    @Test
    fun `rejects inverted coordinates`() {
        assertFailsWith<IllegalArgumentException> { CropRect(0.9f, 0.1f, 0.1f, 0.9f) }
        assertFailsWith<IllegalArgumentException> { CropRect(0.1f, 0.9f, 0.9f, 0.1f) }
    }

    @Test
    fun `rejects out of range coordinates`() {
        assertFailsWith<IllegalArgumentException> { CropRect(-0.1f, 0f, 1f, 1f) }
        assertFailsWith<IllegalArgumentException> { CropRect(0f, 0f, 1.1f, 1f) }
    }

    @Test
    fun `rejects zero area`() {
        assertFailsWith<IllegalArgumentException> { CropRect(0.5f, 0f, 0.5f, 1f) }
    }

    @Test
    fun `sanitized swaps inverted corners and clamps`() {
        val rect = CropRect.sanitized(left = 1.2f, top = 0.8f, right = 0.3f, bottom = -0.5f)
        assertNotNull(rect)
        assertEquals(CropRect(0.3f, 0f, 1f, 0.8f), rect)
    }

    @Test
    fun `sanitized returns null for degenerate input`() {
        assertNull(CropRect.sanitized(0.5f, 0.5f, 0.5f, 0.5f))
        assertNull(CropRect.sanitized(2f, 2f, 3f, 3f))
    }

    @Test
    fun `width and height`() {
        val rect = CropRect(0.25f, 0.25f, 0.75f, 1f)
        assertEquals(0.5f, rect.width, 1e-6f)
        assertEquals(0.75f, rect.height, 1e-6f)
    }
}
