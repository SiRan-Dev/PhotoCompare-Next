package com.sirandev.photocompare.ui.compare

import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.sirandev.photocompare.ui.compare.zoomable.ZoomableState
import com.sirandev.photocompare.ui.compare.zoomable.startFling
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Full-screen pan/zoom surface used while "sync zoom and pan" is enabled.
 *
 * While sync is on, the two compare panes are treated as ONE gesture area: a pinch or
 * single-finger drag started anywhere on the whole compare screen — on either window, in a
 * letterbox/empty region, or even with fingers straddling the divider — drives the shared
 * pan/zoom. The gesture is applied to the zoom state of the pane under the initial touch;
 * [CompareMediator] then mirrors it to the other pane, so there is no "top window vs bottom
 * window" distinction anymore.
 *
 * Pager cooperation matches the per-image `zoomable` rules:
 * - pinch (multi-touch) anywhere → zoom;
 * - single finger while zoomed in → pan (and fling), consumed early so the pager never steals
 *   the horizontal drag;
 * - single finger at fit scale → not consumed, the HorizontalPager still pages normally;
 * - double tap toggles between fit and a closer zoom;
 * - long press (stationary) keeps the Live Photo playback gesture working.
 *
 * The modifier must be applied to a node that contains BOTH panes stacked as two equal-height
 * halves (the compare screen content column).
 */
internal fun Modifier.syncZoomAndPanGestures(
    topZoom: () -> ZoomableState?,
    bottomZoom: () -> ZoomableState?,
    onLiveStart: () -> Unit = {},
    onLiveEnd: () -> Unit = {},
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val flingSpec: DecayAnimationSpec<Float> = exponentialDecay()

    fun stateFor(side: PaneSide): ZoomableState? =
        if (side == PaneSide.TOP) topZoom() else bottomZoom()

    fun otherSide(side: PaneSide): PaneSide =
        if (side == PaneSide.TOP) PaneSide.BOTTOM else PaneSide.TOP

    /** A ready zoom state for [side], falling back to the other pane if it is not ready yet. */
    fun readyState(side: PaneSide): ZoomableState? =
        stateFor(side)?.takeIf { it.isReady } ?: stateFor(otherSide(side))?.takeIf { it.isReady }

    /** Viewport coordinate of [pos] mapped into the pane [side] (both panes are full-width
     * halves of the gesture node, split vertically at half of its height). */
    fun paneLocalY(posY: Float, side: PaneSide, divider: Float): Float =
        if (side == PaneSide.TOP) posY else posY - divider

    this
        // double tap: toggle zoom around the tapped spot
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = { pos ->
                    val divider = size.height / 2f
                    val side = if (pos.y < divider) PaneSide.TOP else PaneSide.BOTTOM
                    val zoom = readyState(side) ?: return@detectTapGestures
                    val target = if (zoom.isFit) zoom.minScale * 3f else zoom.minScale
                    zoom.applyGesture(
                        target / zoom.scale, 0f, 0f, pos.x, paneLocalY(pos.y, side, divider),
                    )
                },
            )
        }
        // long press: Live Photo playback (same semantics as the per-image zoomable)
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val longPress = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when (event.type) {
                            PointerEventType.Release ->
                                return@withTimeoutOrNull false

                            PointerEventType.Move -> {
                                val change = event.changes.firstOrNull() ?: continue
                                if (event.changes.size > 1 ||
                                    abs(change.position.x - down.position.x) > viewConfiguration.touchSlop ||
                                    abs(change.position.y - down.position.y) > viewConfiguration.touchSlop
                                ) {
                                    return@withTimeoutOrNull false
                                }
                            }

                            else -> {
                                // a second finger landing cancels the long press
                                if (event.changes.size > 1) return@withTimeoutOrNull false
                            }
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
                if (longPress == null) {
                    onLiveStart()
                    // consume everything until release so playback is not interrupted by pan/zoom
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        if (event.type == PointerEventType.Release || event.changes.none { it.pressed }) break
                    }
                    onLiveEnd()
                }
            }
        }
        // pinch / pan / fling — processed on the INITIAL pass so this surface can claim the
        // gesture before the descendant HorizontalPagers do
        .pointerInput(Unit) {
            val divider = size.height / 2f
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val downSide = if (down.position.y < divider) PaneSide.TOP else PaneSide.BOTTOM
                // Drive the pane under the initial touch; both are mirrored anyway, so a gesture
                // on either window (or straddling the divider) has the same effect.
                var side = downSide
                var zoom = readyState(side)
                var consuming = false
                var gestureMoved = false
                var sawMultiTouch = false
                var prevUptime = down.uptimeMillis
                var prevPos = down.position
                var lastUptime = down.uptimeMillis
                var lastPos = down.position
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    when (event.type) {
                        PointerEventType.Move -> {
                            val multiTouch = event.changes.size > 1
                            if (multiTouch) sawMultiTouch = true
                            if (zoom == null) {
                                // image not ready when the gesture started — try again each move
                                zoom = readyState(side)
                            }
                            if (zoom != null) {
                                val gestureZoom = if (sawMultiTouch) event.calculateZoom() else 1f
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid()
                                val shouldConsume = sawMultiTouch || !zoom.isFit
                                val moved = pan != Offset.Zero || gestureZoom != 1f
                                if (shouldConsume && moved) {
                                    val yOffset = paneLocalY(centroid.y, side, divider)
                                    if (zoom.applyGesture(
                                            gestureZoom,
                                            pan.x,
                                            pan.y,
                                            centroid.x,
                                            yOffset,
                                        )
                                    ) {
                                        gestureMoved = true
                                    }
                                    consuming = true
                                }
                                val pressed = event.changes.firstOrNull { it.pressed }
                                if (pressed != null && pressed.uptimeMillis != lastUptime) {
                                    prevUptime = lastUptime
                                    prevPos = lastPos
                                    lastUptime = pressed.uptimeMillis
                                    lastPos = pressed.position
                                }
                                if (consuming) event.changes.forEach { it.consume() }
                            }
                        }

                        else -> Unit
                    }
                    val anyPressed = event.changes.any { it.pressed }
                    if (!anyPressed) {
                        if (gestureMoved && lastUptime > prevUptime) {
                            val dtSec = (lastUptime - prevUptime) / 1_000_000_000f
                            if (dtSec > 0f && zoom != null) {
                                startFling(
                                    scope,
                                    zoom,
                                    flingSpec,
                                    (lastPos.x - prevPos.x) / dtSec,
                                    (lastPos.y - prevPos.y) / dtSec,
                                )
                            }
                        }
                        break
                    }
                }
            }
        }
}
