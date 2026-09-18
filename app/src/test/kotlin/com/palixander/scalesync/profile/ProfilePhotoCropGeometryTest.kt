package com.palixander.scalesync.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePhotoCropGeometryTest {
    @Test
    fun `portrait minimum zoom covers viewport and centers vertical crop`() {
        val geometry = ProfilePhotoCropGeometry(100, 200, 300f)

        assertEquals(3f, geometry.minimumDisplayScale, EPSILON)
        assertRect(geometry.sourceCropRect(ProfilePhotoCropTransform()), 0f, 50f, 100f, 150f)
    }

    @Test
    fun `landscape minimum zoom covers viewport and centers horizontal crop`() {
        val geometry = ProfilePhotoCropGeometry(200, 100, 300f)

        assertEquals(3f, geometry.minimumDisplayScale, EPSILON)
        assertRect(geometry.sourceCropRect(ProfilePhotoCropTransform()), 50f, 0f, 150f, 100f)
    }

    @Test
    fun `square source remains centered at minimum zoom`() {
        val geometry = ProfilePhotoCropGeometry(100, 100, 250f)

        assertRect(geometry.sourceCropRect(ProfilePhotoCropTransform()), 0f, 0f, 100f, 100f)
    }

    @Test
    fun `zoom is bounded and maximum zoom maps to smaller source square`() {
        val geometry = ProfilePhotoCropGeometry(200, 100, 300f, maxZoom = 4f)

        val below = geometry.sourceCropRect(ProfilePhotoCropTransform(zoom = 0.1f))
        val above = geometry.sourceCropRect(ProfilePhotoCropTransform(zoom = 10f))

        assertEquals(100f, below.width, EPSILON)
        assertEquals(25f, above.width, EPSILON)
        assertEquals(37.5f, above.top, EPSILON)
    }

    @Test
    fun `normalized pan maps exactly to source crop bounds`() {
        val geometry = ProfilePhotoCropGeometry(200, 100, 300f)

        assertRect(
            geometry.sourceCropRect(ProfilePhotoCropTransform(panX = -1f)),
            0f, 0f, 100f, 100f,
        )
        assertRect(
            geometry.sourceCropRect(ProfilePhotoCropTransform(panX = 1f)),
            100f, 0f, 200f, 100f,
        )
    }

    @Test
    fun `pan and non finite values are clamped`() {
        val geometry = ProfilePhotoCropGeometry(200, 100, 300f)

        val moved = geometry.panBy(ProfilePhotoCropTransform(), 1_000f, Float.NaN)

        assertEquals(1f, moved.panX, EPSILON)
        assertEquals(0f, moved.panY, EPSILON)
        assertEquals(1f, moved.zoom, EPSILON)
    }

    @Test
    fun `integer bounds stay square and inside source after fractional pan`() {
        val geometry = ProfilePhotoCropGeometry(101, 67, 299f)

        val crop = geometry.sourceCropBounds(
            ProfilePhotoCropTransform(zoom = 1.7f, panX = 0.37f, panY = -0.41f),
        )

        assertTrue(crop.size > 0)
        assertTrue(crop.left >= 0)
        assertTrue(crop.top >= 0)
        assertTrue(crop.left + crop.size <= 101)
        assertTrue(crop.top + crop.size <= 67)
    }

    @Test
    fun `integer bounds reach exact source edges`() {
        val geometry = ProfilePhotoCropGeometry(101, 67, 299f)

        val leading = geometry.sourceCropBounds(ProfilePhotoCropTransform(panX = -1f))
        val trailing = geometry.sourceCropBounds(ProfilePhotoCropTransform(panX = 1f))

        assertEquals(0, leading.left)
        assertEquals(101, trailing.left + trailing.size)
    }

    private fun assertRect(
        actual: ProfilePhotoCropRect,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        assertEquals(left, actual.left, EPSILON)
        assertEquals(top, actual.top, EPSILON)
        assertEquals(right, actual.right, EPSILON)
        assertEquals(bottom, actual.bottom, EPSILON)
    }

    companion object {
        private const val EPSILON = 0.001f
    }
}
