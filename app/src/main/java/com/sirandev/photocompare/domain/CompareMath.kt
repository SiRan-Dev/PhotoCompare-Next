package com.sirandev.photocompare.domain

import kotlin.math.sqrt

/**
 * Pure math for the compare screen, ported 1:1 from the original PhotoViewMediator.
 * Kept free of Android dependencies so it can be unit-tested on the JVM and
 * parity-checked against the original implementation.
 */

/** Simple 2D float point (android-free). */
data class F2(val x: Float, val y: Float)

/** Simple 2D dimension (source image size). */
data class Dim(val width: Float, val height: Float) {
    val diagonal: Float
        get() = sqrt(width * width + height * height)
}

/**
 * Pan/zoom of one pane. [scale] maps source pixels to viewport pixels;
 * [center] is the source-coordinate center of the viewport (null when unknown).
 */
data class PanZoom(val scale: Float, val center: F2?)

object CompareMath {

    /**
     * Initial scale offset derived from differing image dimensions, so that a 12MP and a
     * 24MP image start out "same visual size".
     */
    fun getDimensionScaleOffset(topDimension: Dim, bottomDimension: Dim): Float =
        bottomDimension.diagonal / topDimension.diagonal

    /**
     * Offset between the two views' center points, as a ratio-of-ratios on each axis.
     * Port of PhotoViewMediator.getRelativeOffsetAsPoint().
     */
    fun getRelativeOffset(
        topDimension: Dim,
        topCenterPoint: F2,
        bottomDimension: Dim,
        bottomCenterPoint: F2,
    ): F2 = F2(
        x = (topCenterPoint.x / topDimension.width) / (bottomCenterPoint.x / bottomDimension.width),
        y = (topCenterPoint.y / topDimension.height) / (bottomCenterPoint.y / bottomDimension.height),
    )

    /**
     * Port of PhotoViewMediator.applyDimensionPointOffset(). Compensates pan between images of
     * differing resolutions, combined with the stored center offset.
     */
    fun applyDimensionPointOffset(
        topDimension: Dim,
        bottomDimension: Dim,
        sourcePoint: F2,
        sourceIsBottom: Boolean,
        centerOffset: F2?,
    ): F2 {
        val relativeX: Float
        val relativeY: Float
        if (sourceIsBottom) {
            relativeX = sourcePoint.x / bottomDimension.width * topDimension.width
            relativeY = sourcePoint.y / bottomDimension.height * topDimension.height
        } else {
            relativeX = sourcePoint.x / topDimension.width * bottomDimension.width
            relativeY = sourcePoint.y / topDimension.height * bottomDimension.height
        }
        return when (centerOffset) {
            null -> F2(relativeX, relativeY)
            else ->
                if (sourceIsBottom) {
                    F2(relativeX * centerOffset.x, relativeY * centerOffset.y)
                } else {
                    F2(relativeX / centerOffset.x, relativeY / centerOffset.y)
                }
        }
    }

    /**
     * Port of PhotoViewMediator.applyOffset(): applies the stored pan/zoom offset to a source
     * state. Note the direction convention: bottom → top multiplies, top → bottom divides.
     */
    fun applyOffset(
        sourcePanAndZoom: PanZoom,
        panAndZoomOffset: PanZoom,
        topDimension: Dim,
        bottomDimension: Dim,
        sourceIsBottom: Boolean,
    ): PanZoom {
        val scaleWithOffset = if (sourceIsBottom) {
            sourcePanAndZoom.scale * panAndZoomOffset.scale
        } else {
            sourcePanAndZoom.scale / panAndZoomOffset.scale
        }
        val centerSource = sourcePanAndZoom.center
        val centerWithOffset = centerSource?.let {
            applyDimensionPointOffset(topDimension, bottomDimension, it, sourceIsBottom, panAndZoomOffset.center)
        }
        return PanZoom(scaleWithOffset, centerWithOffset)
    }

    /**
     * Viewport coordinates for a source point, given scale & center: v(p) = dstCenter + (p − center) × scale.
     */
    fun sourceToViewport(p: F2, center: F2, scale: Float, dstWidth: Float, dstHeight: Float): F2 =
        F2(dstWidth / 2f + (p.x - center.x) * scale, dstHeight / 2f + (p.y - center.y) * scale)

    /**
     * Source point currently under a viewport position: p(v) = center + (v − dstCenter) / scale.
     */
    fun viewportToSource(v: F2, center: F2, scale: Float, dstWidth: Float, dstHeight: Float): F2 =
        F2(center.x + (v.x - dstWidth / 2f) / scale, center.y + (v.y - dstHeight / 2f) / scale)

    /**
     * New center after zooming around a viewport centroid, keeping the source point under the
     * centroid fixed: center' = center + (c − dstCenter) × (1/s − 1/s').
     */
    fun zoomAroundCentroid(center: F2, centroid: F2, oldScale: Float, newScale: Float, dstWidth: Float, dstHeight: Float): F2 =
        F2(
            x = center.x + (centroid.x - dstWidth / 2f) * (1f / oldScale - 1f / newScale),
            y = center.y + (centroid.y - dstHeight / 2f) * (1f / oldScale - 1f / newScale),
        )

    /**
     * New center after panning by [dx, dy] viewport pixels: center' = center − d / scale.
     */
    fun pan(center: F2, dx: Float, dy: Float, scale: Float): F2 = F2(center.x - dx / scale, center.y - dy / scale)

    /**
     * Clamp the viewport center within pan bounds (SSIV semantics): on each axis, if the scaled
     * source fills the viewport, the center is limited to [dst/(2s), src − dst/(2s)]; otherwise
     * the axis is locked to the source midpoint.
     */
    fun clampCenter(center: F2, src: Dim, dstWidth: Float, dstHeight: Float, scale: Float): F2 = F2(
        x = clampAxis(center.x, src.width, dstWidth, scale),
        y = clampAxis(center.y, src.height, dstHeight, scale),
    )

    private fun clampAxis(centerAxis: Float, srcAxis: Float, dstAxis: Float, scale: Float): Float =
        if (srcAxis * scale >= dstAxis) {
            centerAxis.coerceIn(dstAxis / (2f * scale), srcAxis - dstAxis / (2f * scale))
        } else {
            srcAxis / 2f
        }
}
