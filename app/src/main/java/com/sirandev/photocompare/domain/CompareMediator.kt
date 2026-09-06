package com.sirandev.photocompare.domain

import com.sirandev.photocompare.ui.compare.PaneSide

/**
 * Coordinates the top and bottom compare panes. Kotlin port of the original
 * PhotoViewMediator; the pure math lives in [CompareMath] (unit-tested for parity).
 *
 * Threading: all methods are called from the main thread (Compose gesture / snapshot
 * dispatch), no locking required.
 */
class CompareMediator(
    private val topPane: PaneBridge,
    private val bottomPane: PaneBridge,
) {

    /** View-level facade for one pane, implemented by the compare screen state holders. */
    interface PaneBridge {
        val side: PaneSide
        val currentIndex: Int

        /** True source-image dimension (orientation-corrected), or null while loading. */
        val sourceDimension: Dim?

        /** Current pan/zoom, or null while loading. */
        val panZoom: PanZoom?

        /** Programmatic pan/zoom write; the implementation must suppress change events. */
        fun setPanAndZoom(target: PanZoom)

        /** Reset to fit state (minScale, no center), suppressing change events. */
        fun resetPanZoom()
    }

    var syncZoomAndPan: Boolean = true

    /** Size of the shared image list; used for index bounds checks. */
    var listSize: Int = 0

    private var topViewIndex = NO_VALID_IMAGE_INDEX
    private var bottomViewIndex = NO_VALID_IMAGE_INDEX

    /**
     * Offset of scale and pan between top and bottom image such that offsets are applied
     * positively (+ and ×) from bottom to top, and negatively (− and ÷) from top to bottom.
     */
    private var panAndZoomOffset: PanZoom? = null

    fun initIndexes(topIndex: Int, bottomIndex: Int) {
        topViewIndex = topIndex
        bottomViewIndex = bottomIndex
    }

    fun getTopIndex(): Int = topPane.currentIndex

    fun getBottomIndex(): Int = bottomPane.currentIndex

    /**
     * Page-settle arbitration: rejects positions equal to the other pane's current page.
     *
     * @return true when accepted (caller should keep the page), false when the pager must be
     * corrected to [getNextValidIndex]
     */
    fun onPageSelected(source: PaneBridge, position: Int): Boolean {
        val other = otherPane(source)
        if (position == other.currentIndex) {
            return false
        }
        if (source.side == PaneSide.TOP) {
            topViewIndex = position
        } else {
            bottomViewIndex = position
        }
        // note that panAndZoomOffset is kept, assuming that an appropriate image view state was used to load the next image
        return true
    }

    /**
     * Direction-aware "next valid index" after a rejected page selection.
     * Port of PhotoViewMediator.getNextValidImageIndex(ImageDetailView, int).
     */
    fun getNextValidIndex(source: PaneBridge, invalidIndex: Int): Int {
        val lastValidIndex = if (source.side == PaneSide.TOP) topViewIndex else bottomViewIndex
        // did user navigate to the right?
        val searchUpwards = (invalidIndex > lastValidIndex || invalidIndex == 0) &&
            invalidIndex < (listSize - 2)
        return getNextValidIndex(source, searchUpwards)
    }

    private fun getNextValidIndex(source: PaneBridge, isDirectionUp: Boolean): Int {
        var candidate = otherPane(source).currentIndex
        return if (isDirectionUp) {
            candidate++
            if (candidate >= listSize) NO_VALID_IMAGE_INDEX else candidate
        } else {
            candidate--
            if (candidate < 0) NO_VALID_IMAGE_INDEX else candidate
        }
    }

    /** Propagate pan/zoom change from one view to the other. */
    fun onPanOrZoomChanged(source: PaneBridge) {
        if (syncZoomAndPan) {
            copyPanAndZoom(source, otherPane(source))
        } else {
            updatePanAndZoomOffset()
        }
    }

    private fun copyPanAndZoom(source: PaneBridge, target: PaneBridge) {
        val offset = panAndZoomOffset ?: initPanAndZoomOffset()
        val sourcePanZoom = source.panZoom ?: return
        val topDim = topPane.sourceDimension ?: return
        val bottomDim = bottomPane.sourceDimension ?: return
        val targetPanZoom = CompareMath.applyOffset(
            sourcePanAndZoom = sourcePanZoom,
            panAndZoomOffset = offset,
            topDimension = topDim,
            bottomDimension = bottomDim,
            sourceIsBottom = source.side == PaneSide.BOTTOM,
        )
        target.setPanAndZoom(targetPanZoom)
    }

    /** Create an initial [panAndZoomOffset] based on the image dimension ratio. */
    private fun initPanAndZoomOffset(): PanZoom {
        val topDimension = topPane.sourceDimension ?: Dim(1f, 1f)
        val bottomDimension = bottomPane.sourceDimension ?: Dim(1f, 1f)
        val dimensionScaleOffset = CompareMath.getDimensionScaleOffset(topDimension, bottomDimension)
        return PanZoom(dimensionScaleOffset, null).also { panAndZoomOffset = it }
    }

    /**
     * Update the internal offset when the two panes are not synchronized.
     * Note that it would be wrong to apply getDimensionScaleOffset() again here.
     */
    private fun updatePanAndZoomOffset() {
        val topPanAndZoom = topPane.panZoom
        val topDimension = topPane.sourceDimension
        val bottomPanAndZoom = bottomPane.panZoom
        val bottomDimension = bottomPane.sourceDimension
        if (topPanAndZoom == null || bottomPanAndZoom == null || topDimension == null || bottomDimension == null) {
            return
        }
        val scaleOffset = topPanAndZoom.scale / bottomPanAndZoom.scale
        val topCenterPoint = topPanAndZoom.center
        val bottomCenterPoint = bottomPanAndZoom.center
        panAndZoomOffset = if (topCenterPoint != null && bottomCenterPoint != null) {
            val centerOffset = CompareMath.getRelativeOffset(topDimension, topCenterPoint, bottomDimension, bottomCenterPoint)
            PanZoom(scaleOffset, centerOffset)
        } else {
            PanZoom(scaleOffset, null)
        }
    }

    fun resetState() {
        topPane.resetPanZoom()
        bottomPane.resetPanZoom()
        panAndZoomOffset = null
    }

    private fun otherPane(source: PaneBridge): PaneBridge = if (source.side == PaneSide.TOP) bottomPane else topPane

    companion object {
        const val NO_VALID_IMAGE_INDEX = -999
    }
}
