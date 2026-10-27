package com.example.appletvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.appletvremote.R
import com.example.appletvremote.protocol.companion.TouchPhase
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Apple-style touch surface.
 *
 * A tap is a real SELECT press, a long hold opens the contextual menu, and a
 * drag is forwarded as PRESS/HOLD/RELEASE samples.
 *
 * The swipe works like the one in Clicker, which was confirmed against a real
 * TV:
 *
 *  - Every gesture starts at the **centre** of the virtual 0..1000 surface, not
 *    under the finger. tvOS reads the movement between samples, so starting
 *    near an edge used to make one or two directions impossible: there was no
 *    room left to move.
 *  - After the press, the finger's own motion is accumulated and scaled up by
 *    [GAIN_UNITS_PER_DP]. The phone pad is larger than the TV's surface, so the
 *    gain restores the physical scale of a real remote: moving the finger about
 *    16 mm from the centre reaches an edge, and a slow 10 mm swipe delivers a
 *    full directional step. A quick flick still arrives well above the speed
 *    tvOS reads as a flick.
 *  - HOLD samples go out at most every [HOLD_INTERVAL_NS] and only when the
 *    finger has actually moved. Unlike the old per-event threshold, the filter
 *    accumulates skipped movement instead of discarding it, so a slow swipe is
 *    no longer silently swallowed.
 *  - Reaching an edge sends RELEASE at that edge and ends the touch stream, as
 *    a finger sliding off the real remote would. Pinning the finger at the edge
 *    made tvOS see it stop and lift with no velocity, which jerked focus back.
 *
 * Taps are separated from swipes by the platform touch slop. Everything the
 * finger moved before that point is a dead zone and is not forwarded, so the
 * first sample after PRESS is a clean, small step instead of a velocity spike.
 */
@Composable
fun Touchpad(
    modifier: Modifier = Modifier,
    swipeGuard: Boolean = true,
    onTouch: (x: Int, y: Int, phase: TouchPhase) -> Unit,
    onSelect: () -> Unit,
    onHoldSelect: () -> Unit,
) {
    val haptics = rememberHaptics()
    var pressed by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    // Material 3 surface: a tonal pane that sits one step above the
    // background. While the finger is down the outline switches to the accent
    // colour, the only state cue the pad needs.
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(scheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = if (pressed) scheme.primary else scheme.outlineVariant,
                shape = shape,
            )
            .pointerInput(swipeGuard) {
                // A tap may drift a little. Android's own touch slop is the
                // right threshold, with a floor for devices that report a very
                // small one.
                val commitPx = viewConfiguration.touchSlop.coerceAtLeast(6.dp.toPx())
                // 0..1000 units per density-independent pixel of finger travel.
                val unitsPerPx = GAIN_UNITS_PER_DP / density
                val minStep = if (swipeGuard) MIN_STEP_UNITS else 0f

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    haptics.touchDown()

                    var touchOpen = false
                    var lastX = CENTRE.toFloat()
                    var lastY = CENTRE.toFloat()

                    try {
                        var committed: PointerInputChange? = null
                        var tapped = false

                        // Wait for one of the three endings: lift (tap), a real
                        // movement (swipe), or the long-press timeout (menu).
                        val waitFinished = withTimeoutOrNull(
                            viewConfiguration.longPressTimeoutMillis,
                        ) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                // Distance is checked before the lift: a quick
                                // short flick delivers its whole travel in the
                                // same event that ends the touch, and checking
                                // `changedToUp` first used to turn it into a tap.
                                if ((change.position - down.position).getDistance() > commitPx) {
                                    committed = change
                                    break
                                }
                                if (change.changedToUp()) {
                                    tapped = true
                                    break
                                }
                                change.consume()
                            }
                            true
                        }

                        if (waitFinished == null) {
                            // Long press: confirm it physically the moment it is
                            // recognised, then send the held select.
                            haptics.longPress()
                            onHoldSelect()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                if (change.changedToUp()) break
                                change.consume()
                            }
                            return@awaitEachGesture
                        }

                        val start = committed
                        // An up event can also carry a large jump (a very quick
                        // flick). Starting a touch on it would produce a bare
                        // PRESS+RELEASE in the centre, which tvOS may read as a
                        // second click, so such a touch stays a plain tap.
                        if (start == null || tapped || start.changedToUp()) {
                            // A short touch is a click, even if the finger
                            // drifted a little below the commit distance.
                            haptics.tap()
                            onSelect()
                            return@awaitEachGesture
                        }

                        // A swipe. The press always lands in the middle of the
                        // TV's surface; the dead zone before this moment is not
                        // forwarded, so the first sample is small and clean.
                        onTouch(CENTRE, CENTRE, TouchPhase.PRESS)
                        touchOpen = true
                        lastX = CENTRE.toFloat()
                        lastY = CENTRE.toFloat()

                        var sentX = lastX
                        var sentY = lastY
                        var lastSendNanos = System.nanoTime()
                        var previous = start.position

                        var change: PointerInputChange? = start
                        while (true) {
                            val current = change
                                ?: awaitPointerEvent()
                                    .changes
                                    .firstOrNull { it.id == down.id }
                                ?: break
                            change = null

                            val dx = current.position.x - previous.x
                            val dy = current.position.y - previous.y
                            previous = current.position
                            lastX += dx * unitsPerPx
                            lastY += dy * unitsPerPx

                            // Sliding off the edge: lift there and stop sending
                            // samples. The TV then gets a release that is still
                            // moving, so the flick carries on instead of dying.
                            if (lastX < 0f || lastX > TOUCH_RANGE.toFloat() ||
                                lastY < 0f || lastY > TOUCH_RANGE.toFloat()
                            ) {
                                lastX = lastX.coerceIn(0f, TOUCH_RANGE.toFloat())
                                lastY = lastY.coerceIn(0f, TOUCH_RANGE.toFloat())
                                onTouch(lastX.roundToInt(), lastY.roundToInt(), TouchPhase.RELEASE)
                                touchOpen = false
                                // The rest of the gesture belongs to the pad,
                                // but there is no finger left to follow.
                                if (!current.changedToUp()) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val up = event.changes.firstOrNull { it.id == down.id }
                                            ?: break
                                        if (up.changedToUp()) break
                                        up.consume()
                                    }
                                }
                                return@awaitEachGesture
                            }

                            val now = System.nanoTime()
                            val distance = hypot(lastX - sentX, lastY - sentY)
                            val movedEnough = distance > 0f && distance >= minStep
                            if (movedEnough && now - lastSendNanos >= HOLD_INTERVAL_NS) {
                                sentX = lastX
                                sentY = lastY
                                lastSendNanos = now
                                onTouch(lastX.roundToInt(), lastY.roundToInt(), TouchPhase.HOLD)
                            }

                            if (current.changedToUp()) break
                            current.consume()
                        }

                        // Release where the finger actually stopped, including
                        // any movement since the last throttled sample.
                        onTouch(lastX.roundToInt(), lastY.roundToInt(), TouchPhase.RELEASE)
                        touchOpen = false
                    } finally {
                        if (touchOpen) {
                            // The gesture was cancelled mid-swipe: never leave
                            // the TV with a finger held down.
                            onTouch(lastX.roundToInt(), lastY.roundToInt(), TouchPhase.RELEASE)
                        }
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.touchpad_hint),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
        )
    }
}

/** Virtual touch surface used by the Companion protocol. */
private const val TOUCH_RANGE = 1000

/** Where every gesture starts: the middle of the virtual surface. */
private const val CENTRE = TOUCH_RANGE / 2

/**
 * Virtual units per dp of finger travel. Clicker measured that 1000 units on a
 * real remote correspond to roughly 33 mm of finger travel (about 208 dp), so
 * one screen-independent pixel of travel is worth about five units here. That
 * keeps the physical feel: ~16 mm from the centre to an edge and a slow 10 mm
 * swipe long enough for one focus step.
 */
private const val GAIN_UNITS_PER_DP = 5f

/**
 * Smoothest movement that is still forwarded when the jitter filter is on.
 * Below this the sample is not dropped, only delayed: the next sample carries
 * the accumulated distance.
 */
private const val MIN_STEP_UNITS = 4f

/** ~62 samples per second, matching pyatv's TOUCHPAD_DELAY_MS. */
private const val HOLD_INTERVAL_NS = 16_000_000L
