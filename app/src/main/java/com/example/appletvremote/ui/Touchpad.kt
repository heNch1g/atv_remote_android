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
import kotlin.math.roundToInt

/**
 * Apple-style touch surface.
 *
 * A tap is a real SELECT press, a long hold opens the contextual menu, and a
 * drag is forwarded as PRESS/HOLD/RELEASE samples.
 *
 * The swipe follows the reference implementation in pyatv, which is known to
 * work against tvOS:
 *
 *  - The protocol expects absolute coordinates on a virtual 0..1000 surface,
 *    so every sample is the finger's own position mapped onto that surface.
 *    There is no virtual accumulator, no fixed centre and no acceleration.
 *  - PRESS goes out at the point where the finger landed, and every HOLD is a
 *    later finger position. tvOS derives direction from the deltas between
 *    consecutive samples, so the first delta already points the way the finger
 *    actually travelled. This is what removes the initial reverse flick that
 *    appeared when a gesture was started from the pad centre.
 *  - HOLD samples are throttled to roughly one every 16 ms, the same cadence
 *    pyatv uses. RELEASE is sent at the last finger position.
 *
 * [swipeGuard] is only a small-jitter filter: movements shorter than
 * [MIN_STEP] virtual units are not forwarded, so a resting finger does not
 * spray noise at the TV. It no longer locks an axis or blocks reversals — a
 * deliberate change of direction is followed immediately.
 *
 * Taps have a commit distance larger than the system touch slop: a tap whose
 * finger drifts a few pixels must not become a micro-swipe, which video
 * players turn into a ±10 second skip.
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
                val commitPx = COMMIT_DISTANCE.toPx()
                val padWidth = size.width.toFloat().coerceAtLeast(1f)
                val padHeight = size.height.toFloat().coerceAtLeast(1f)
                val minStep = if (swipeGuard) MIN_STEP else 0

                fun padX(x: Float): Int =
                    (x / padWidth * TOUCH_RANGE).roundToInt().coerceIn(0, TOUCH_RANGE)

                fun padY(y: Float): Int =
                    (y / padHeight * TOUCH_RANGE).roundToInt().coerceIn(0, TOUCH_RANGE)

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    haptics.touchDown()

                    try {
                        var dragging = false
                        var upDuringWait = false
                        var dragChange: PointerInputChange? = null

                        // Wait for one of the three endings: lift (tap), a real
                        // movement (swipe), or the long-press timeout (menu).
                        val waitFinished = withTimeoutOrNull(
                            viewConfiguration.longPressTimeoutMillis,
                        ) {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                if (change.changedToUp()) {
                                    upDuringWait = true
                                    break
                                }
                                if ((change.position - down.position)
                                        .getDistance() > commitPx
                                ) {
                                    dragging = true
                                    dragChange = change
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

                        if (!dragging || upDuringWait) {
                            // A short touch is a click, even if the finger
                            // drifted a little below the commit distance.
                            haptics.tap()
                            onSelect()
                            return@awaitEachGesture
                        }

                        // The swipe starts exactly where the finger landed.
                        var lastX = padX(down.position.x)
                        var lastY = padY(down.position.y)
                        var finalX = lastX
                        var finalY = lastY
                        // Let the very first committed sample through instead
                        // of waiting a full frame after PRESS.
                        var lastHoldNanos = System.nanoTime() - HOLD_INTERVAL_NS

                        onTouch(lastX, lastY, TouchPhase.PRESS)

                        var change = dragChange
                        while (true) {
                            if (change == null) {
                                val event = awaitPointerEvent()
                                change = event.changes.firstOrNull { it.id == down.id } ?: break
                            }
                            val current = change ?: break
                            change = null

                            finalX = padX(current.position.x)
                            finalY = padY(current.position.y)

                            val dx = finalX - lastX
                            val dy = finalY - lastY
                            val movedEnough = dx * dx + dy * dy >= minStep * minStep
                            val now = System.nanoTime()
                            val due = now - lastHoldNanos >= HOLD_INTERVAL_NS

                            if (!current.changedToUp() && movedEnough && due) {
                                onTouch(finalX, finalY, TouchPhase.HOLD)
                                lastX = finalX
                                lastY = finalY
                                lastHoldNanos = now
                            }

                            if (current.changedToUp()) break
                            current.consume()
                        }

                        onTouch(finalX, finalY, TouchPhase.RELEASE)
                    } finally {
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

/** How far the finger must travel before a touch becomes a swipe. */
private val COMMIT_DISTANCE = 14.dp

/**
 * Smallest movement, in virtual units, that is forwarded while the jitter
 * filter is on. A resting finger moves by a unit or two; a real swipe does not.
 */
private const val MIN_STEP = 8

/** ~62 samples per second, matching pyatv's TOUCHPAD_DELAY_MS. */
private const val HOLD_INTERVAL_NS = 16_000_000L
