package com.sirandev.photocompare.ui.compare.zoomable

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sirandev.photocompare.domain.CompareMath
import com.sirandev.photocompare.domain.Dim
import com.sirandev.photocompare.domain.F2
import com.sirandev.photocompare.domain.PanZoom
import kotlin.math.max
import kotlin.math.min

/**
 * Pan/zoom state for one compare pane, semantically aligned with the original
 * SubsamplingScaleImageView usage (scale relative to source pixels, center in source
 * coordinates) so that the mediator math ports 1:1.
 *
 * The fields are snapshot-backed and read inside `graphicsLayer { }` only, so gestures
 * cause zero recomposition. Programmatic writes are wrapped in [withListenersSuppressed]
 * to prevent sync feedback loops (the Compose equivalent of the original
 * `disableStateChangedListener()` / `enableStateChangedListener()` pair).
 */
class ZoomableState {

    /** Source image size (orientation-corrected), set when the image is loaded. */
    var srcSize: Dim by mutableStateOf(Dim(0f, 0f))
        private set

    /** Viewport size in pixels, set on layout. */
    var viewportWidth: Float = 0f
        private set
    var viewportHeight: Float = 0f
        private set

    private var _bitmapScale by mutableFloatStateOf(1f)

    /**
     * Decoded bitmap size relative to source size (1f when fully decoded). Snapshot-backed:
     * the rendering graphicsLayer reads it, so writes must invalidate the layer regardless
     * of when the Coil success callback fires relative to metadata loading.
     */
    var bitmapScale: Float
        get() = _bitmapScale
        set(value) {
            _bitmapScale = if (value > 0f) value else 1f
            debugLog("bitmapScale=$_bitmapScale")
        }

    var minScale: Float = 1f
        private set

    var maxScale: Float = 8f
        private set

    var scale: Float by mutableFloatStateOf(1f)
        private set

    /** Source-coordinate center of the viewport; null until the first gesture/zoom. */
    var center: F2? by mutableStateOf(null)
        private set

    /** Image has been loaded and laid out. */
    val isReady: Boolean
        get() = srcSize.width > 0f && viewportWidth > 0f

    val isFit: Boolean
        get() = scale <= minScale + FIT_EPSILON

    /** Fired for user-driven state changes (not for suppressed/programmatic writes). */
    var onStateChanged: (() -> Unit)? = null

    @PublishedApi
    internal var suppressionCount = 0

    /**
     * Whether the user has actually zoomed this image. Guards the fit-state reset in
     * [onLayout]: a stray pan must not count as interaction, otherwise scale can stay stuck
     * at its pre-layout value of 1 and the image renders at full size from the top-left.
     */
    private var userZoomed = false

    /** Layout pass; resets zoom limits and clamps the current state. */
    fun onLayout(widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        viewportWidth = widthPx
        viewportHeight = heightPx
        recomputeLimits()
        // onImageLoaded may run before the first layout (viewport still unknown → minScale
        // was 1), which leaves scale stuck at 1; re-establish the fit state until the user
        // has zoomed for real
        if (!userZoomed) {
            scale = minScale
            center = null
        }
    }

    /** Called when a new image (possibly with a different size) is loaded. */
    fun onImageLoaded(widthPx: Float, heightPx: Float, rotationSwapped: Boolean) {
        val w = if (rotationSwapped) heightPx else widthPx
        val h = if (rotationSwapped) widthPx else heightPx
        srcSize = Dim(w, h)
        userZoomed = false
        recomputeLimits()
        scale = minScale
        center = null
    }

    private fun recomputeLimits() {
        if (srcSize.width <= 0f || viewportWidth <= 0f) return
        minScale = min(viewportWidth / srcSize.width, viewportHeight / srcSize.height)
        // allow zooming up to 100% pixel view (or 12× fit for small images)
        maxScale = max(1f, minScale * 12f)
        if (userZoomed) {
            scale = scale.coerceIn(minScale, maxScale)
            center?.let { center = CompareMath.clampCenter(it, srcSize, viewportWidth, viewportHeight, scale) }
        }
    }

    /**
     * Apply a pinch/pan gesture step. [zoom] is multiplicative, [panX]/[panY] viewport px,
     * [centroidX]/[centroidY] the gesture centroid in viewport px.
     *
     * @return true when the state changed
     */
    fun applyGesture(zoom: Float, panX: Float, panY: Float, centroidX: Float, centroidY: Float): Boolean {
        if (!isReady) return false
        var changed = false
        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
        if (newScale != scale) {
            val currentCenter = center ?: F2(srcSize.width / 2f, srcSize.height / 2f)
            center = CompareMath.zoomAroundCentroid(currentCenter, F2(centroidX, centroidY), scale, newScale, viewportWidth, viewportHeight)
            scale = newScale
            userZoomed = true
            changed = true
        }
        // pan only when there is pan room
        if (scale > minScale + FIT_EPSILON && (panX != 0f || panY != 0f)) {
            val currentCenter = center ?: F2(srcSize.width / 2f, srcSize.height / 2f)
            center = CompareMath.pan(currentCenter, panX, panY, scale)
            changed = true
        }
        if (changed) {
            center = center?.let { CompareMath.clampCenter(it, srcSize, viewportWidth, viewportHeight, scale) }
            notifyChanged()
        }
        return changed
    }

    /** Fling continuation step: [dx]/[dy] viewport px for this animation frame. */
    fun panBy(dx: Float, dy: Float): Boolean {
        if (!isReady || center == null) return false
        val currentCenter = center ?: return false
        val moved = CompareMath.pan(currentCenter, dx, dy, scale)
        val clamped = CompareMath.clampCenter(moved, srcSize, viewportWidth, viewportHeight, scale)
        if (clamped == currentCenter) return false
        center = clamped
        notifyChanged()
        return true
    }

    /** Programmatic write used by the mediator (call within suppression). */
    fun setScaleAndCenter(newScale: Float, newCenter: F2?) {
        if (!isReady) return
        scale = newScale.coerceIn(minScale, maxScale)
        center = newCenter?.let { CompareMath.clampCenter(it, srcSize, viewportWidth, viewportHeight, scale) }
        debugLog("setScaleAndCenter: s=$scale c=$center")
    }

    private fun debugLog(msg: String) {
        android.util.Log.d("PhotoCompareNext-Zoom", "$msg | viewport=${viewportWidth}x$viewportHeight src=${srcSize.width}x${srcSize.height} bs=$_bitmapScale minS=$minScale scale=$scale")
    }

    private var lastRenderLog = ""

    /** Called from the graphicsLayer block to log the values actually used for drawing. */
    fun debugRender(scaleX: Float, tx: Float, ty: Float, w: Float, h: Float) {
        val key = "s=$scaleX tx=$tx ty=$ty w=$w h=$h"
        if (key != lastRenderLog) {
            lastRenderLog = key
            debugLog("RENDER $key")
        }
    }

    fun reset() {
        userZoomed = false
        scale = minScale
        center = null
    }

    fun currentState(): PanZoom = PanZoom(scale, center)

    /** Suppress change notifications around programmatic writes. */
    inline fun <T> withListenersSuppressed(block: () -> T): T {
        suppressionCount++
        try {
            return block()
        } finally {
            suppressionCount--
        }
    }

    private fun notifyChanged() {
        if (suppressionCount == 0) {
            onStateChanged?.invoke()
        }
    }

    companion object {
        private const val FIT_EPSILON = 0.001f
    }
}
