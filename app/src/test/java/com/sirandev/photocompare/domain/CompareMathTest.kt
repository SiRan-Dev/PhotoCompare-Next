package com.sirandev.photocompare.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity tests for [CompareMath], replicating the original PhotoViewMediatorUnitTest
 * fixtures (testOnPanOrZoomChangedWithHappyCase / WithZoomingForDifferentImagesSizes).
 */
class CompareMathTest {

    @Test
    fun `applyOffset happy case scale matches original fixture`() {
        // original: top 4000x3000, top zoom 6.5@(320,240), bottom 4000x3000 @1.8@(600,480)
        // sync-off trains offset, then top zooms to 3.0@(300,220) → bottom expects scale 0.83
        val topDim = Dim(4000f, 3000f)
        val bottomDim = Dim(4000f, 3000f)
        // sync-off training state: top @6.5, bottom @1.8 → offset scale = top/bottom ≈ 3.611
        val trained = PanZoom(6.5f / 1.8f, null)
        val offset = CompareMath.applyOffset(
            sourcePanAndZoom = PanZoom(3.0f, F2(300f, 220f)),
            panAndZoomOffset = trained,
            topDimension = topDim,
            bottomDimension = bottomDim,
            sourceIsBottom = false,
        )
        // bottom target = 3.0 / 3.611 ≈ 0.8308 (original expects 0.83)
        assertEquals(0.83f, offset.scale, 0.01f)
        assertTrue(offset.center != null)
    }

    @Test
    fun `applyOffset different image sizes matches original fixture`() {
        // original: top 4032x3024 @2.0 center(2016,1512), bottom 6016x4512 @1.0 → bottom scale 1.34
        val topDim = Dim(4032f, 3024f)
        val bottomDim = Dim(6016f, 4512f)
        val dimensionOffset = PanZoom(CompareMath.getDimensionScaleOffset(topDim, bottomDim), null)
        val offset = CompareMath.applyOffset(
            sourcePanAndZoom = PanZoom(2.0f, F2(2016f, 1512f)),
            panAndZoomOffset = dimensionOffset,
            topDimension = topDim,
            bottomDimension = bottomDim,
            sourceIsBottom = false,
        )
        assertEquals(1.34f, offset.scale, 0.01f)
        assertTrue(offset.center != null)
    }

    @Test
    fun `unknown center propagates as null`() {
        val dim = Dim(4000f, 3000f)
        val offset = CompareMath.applyOffset(
            sourcePanAndZoom = PanZoom(1f, null),
            panAndZoomOffset = PanZoom(1f, null),
            topDimension = dim,
            bottomDimension = dim,
            sourceIsBottom = false,
        )
        assertEquals(1f, offset.scale, 0.001f)
        assertFalse(offset.center != null)
    }

    @Test
    fun `dimension scale offset is ratio of diagonals`() {
        val top = Dim(4032f, 3024f)
        val bottom = Dim(6016f, 4512f)
        assertEquals(7520f / 5040f, CompareMath.getDimensionScaleOffset(top, bottom), 0.001f)
    }

    @Test
    fun `zoom around centroid keeps source point fixed`() {
        val dim = Dim(4000f, 3000f)
        val dstW = 1080f
        val dstH = 810f
        val scale = 0.27f
        val center = F2(2000f, 1500f)
        val centroid = F2(700f, 400f)
        val newScale = 0.54f
        val newCenter = CompareMath.zoomAroundCentroid(center, centroid, scale, newScale, dstW, dstH)
        // source point under centroid must be unchanged
        val before = CompareMath.viewportToSource(centroid, center, scale, dstW, dstH)
        val after = CompareMath.viewportToSource(centroid, newCenter, newScale, dstW, dstH)
        assertEquals(before.x, after.x, 0.01f)
        assertEquals(before.y, after.y, 0.01f)
    }

    @Test
    fun `pan moves center inversely proportional to scale`() {
        val center = F2(2000f, 1500f)
        val moved = CompareMath.pan(center, dx = 100f, dy = -50f, scale = 2f)
        assertEquals(1950f, moved.x, 0.001f)
        assertEquals(1525f, moved.y, 0.001f)
    }

    @Test
    fun `clampCenter locks axis when scaled source smaller than viewport`() {
        val src = Dim(1000f, 1000f)
        val clamped = CompareMath.clampCenter(F2(999f, 999f), src, dstWidth = 2000f, dstHeight = 2000f, scale = 1f)
        assertEquals(500f, clamped.x, 0.001f)
        assertEquals(500f, clamped.y, 0.001f)
    }

    @Test
    fun `clampCenter limits center when scaled source larger than viewport`() {
        val src = Dim(4000f, 3000f)
        val clamped = CompareMath.clampCenter(F2(10f, 10f), src, dstWidth = 1080f, dstHeight = 810f, scale = 1f)
        // dst/(2s) = 540 / 405
        assertEquals(540f, clamped.x, 0.001f)
        assertEquals(405f, clamped.y, 0.001f)
    }

    @Test
    fun `relative offset is ratio of ratios`() {
        val topDim = Dim(4032f, 3024f)
        val bottomDim = Dim(6016f, 4512f)
        val offset = CompareMath.getRelativeOffset(topDim, F2(2016f, 1512f), bottomDim, F2(3008f, 2506f))
        assertEquals((2016f / 4032f) / (3008f / 6016f), offset.x, 0.001f)
        assertEquals((1512f / 3024f) / (2506f / 4512f), offset.y, 0.001f)
    }
}
