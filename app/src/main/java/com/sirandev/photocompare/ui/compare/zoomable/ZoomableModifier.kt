package com.sirandev.photocompare.ui.compare.zoomable

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gesture handling for a compare pane.
 *
 * Consumption rules keep the enclosing HorizontalPager in control where appropriate:
 * - fit state + single finger drag → not consumed, pager scrolls
 * - zoomed state or multi-finger pinch → consumed
 * - double tap toggles between fit and mid zoom
 * - long press (single finger, before slop) triggers [onLongPressStart]/[onLongPressEnd],
 *   reserved for Live Photo playback
 */
fun Modifier.zoomable(
    state: ZoomableState,
    onLongPressStart: ((Offset) -> Unit)? = null,
    onLongPressEnd: (() -> Unit)? = null,
): Modifier = composed {
    val flingSpec: DecayAnimationSpec<Float> = exponentialDecay()
    val scope = rememberCoroutineScope()

    this
        .pointerInput(state) {
            // tap layer: double tap zoom toggle
            detectTapGestures(
                onDoubleTap = { centroid ->
                    if (state.isReady) {
                        val target = if (state.isFit) state.minScale * 3f else state.minScale
                        state.applyGesture(target / state.scale, 0f, 0f, centroid.x, centroid.y)
                    }
                },
            )
        }
        .pointerInput(state, onLongPressStart, onLongPressEnd) {
            if (onLongPressStart == null) return@pointerInput
            val longPressEnd = onLongPressEnd
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // A stationary finger produces NO further pointer events, so the timeout must
                // come from withTimeoutOrNull — waiting for events can never detect it.
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

                            else -> Unit
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
                if (longPress == null) {
                    // timeout elapsed without movement → long press confirmed
                    onLongPressStart?.invoke(down.position)
                    // consume everything until release so pan/zoom stays idle during playback
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        if (event.type == PointerEventType.Release || event.changes.none { it.pressed }) break
                    }
                    longPressEnd?.invoke()
                }
            }
        }
        .pointerInput(state) {
            // transform layer (core): pinch/pan with pager cooperation
            awaitEachGesture {
                var consuming = false
                var gestureMoved = false
                var sawMultiTouch = false
                // velocity estimation from the last two move events of the first pointer
                var prevUptime = 0L
                var prevPos = Offset.Zero
                var lastUptime = 0L
                var lastPos = Offset.Zero
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    when (event.type) {
                        PointerEventType.Move -> {
                            val multiTouch = event.changes.size > 1
                            if (multiTouch) sawMultiTouch = true
                            val zoom = if (sawMultiTouch) event.calculateZoom() else 1f
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val shouldConsume = sawMultiTouch || !state.isFit
                            val moved = pan != Offset.Zero || zoom != 1f
                            if (shouldConsume && moved) {
                                if (state.applyGesture(zoom, pan.x, pan.y, centroid.x, centroid.y)) {
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

                        else -> Unit
                    }
                    val anyPressed = event.changes.any { it.pressed }
                    if (!anyPressed) {
                        if (gestureMoved && lastUptime > prevUptime) {
                            val dtSec = (lastUptime - prevUptime) / 1_000_000_000f
                            if (dtSec > 0f) {
                                startFling(
                                    scope,
                                    state,
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

private fun startFling(
    scope: CoroutineScope,
    state: ZoomableState,
    flingSpec: DecayAnimationSpec<Float>,
    velocityX: Float,
    velocityY: Float,
) {
    if (abs(velocityX) > 100f) {
        scope.launch {
            var last = 0f
            AnimationState(initialValue = 0f, initialVelocity = velocityX).animateDecay(flingSpec) {
                val delta = value - last
                last = value
                if (!state.panBy(delta, 0f)) {
                    cancelAnimation()
                }
            }
        }
    }
    if (abs(velocityY) > 100f) {
        scope.launch {
            var last = 0f
            AnimationState(initialValue = 0f, initialVelocity = velocityY).animateDecay(flingSpec) {
                val delta = value - last
                last = value
                if (!state.panBy(0f, delta)) {
                    cancelAnimation()
                }
            }
        }
    }
}
