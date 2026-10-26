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
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.appletvremote.R
import com.example.appletvremote.protocol.companion.TouchAcceleration
import com.example.appletvremote.protocol.companion.TouchPhase
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Apple-style touch surface.
 *
 * A tap is a real SELECT press, a long hold opens the contextual menu, and a
 * drag is forwarded as PRESS/HOLD/RELEASE samples.
 *
 * Two details matter for a stable swipe:
 *
 *  - The remote touch always **starts at the pad centre**, not under the
 *    finger. tvOS derives direction from the first coordinates it receives;
 *    sending a press near the left edge and then moving right made it read a
 *    brief leftward flick first. Starting every gesture from 500,500 and
 *    accumulating relative movement removes that edge effect entirely.
 *  - [swipeGuard] locks an axis and a direction, drops small backward wobbles
 *    and only allows a reversal after a deliberate pull back. It also makes
 *    the release point the last accepted forward sample, never the raw finger
 *    position, which is what caused reverse flicks on lift.
 *  - The direction is settled from the finger's own travel, scaled without
 *    gain, not from the accelerated virtual point: gain amplifies fast
 *    jitter, which used to lock a rightward settle ahead of a deliberate
 *    leftward swipe (and the same vertically) for its first stretch. The
 *    recent motion must also agree with that travel, so the small contact
 *    roll at touchdown cannot send one step the wrong way.
 *  - Once settled, the virtual point starts from the centre and is nudged
 *    by [INITIAL_STEP] in the chosen direction. The first forwarded sample
 *    therefore always points forward instead of repeating the raw offset
 *    that the filter has not accepted yet.
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
                // The acceleration curve was tuned on a ~300 dp surface; tying
                // it to the actual size would make a small pad twitchy.
                val referencePx = 300.dp.toPx()

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    haptics.touchDown()

                    var dragging = false
                    var upDuringWait = false

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
                        pressed = false
                        return@awaitEachGesture
                    }

                    if (!dragging || upDuringWait) {
                        pressed = false
                        // A short touch is a click, even if the finger drifted
                        // a little below the commit distance.
                        haptics.tap()
                        onSelect()
                        return@awaitEachGesture
                    }

                    var virtualX = CENTRE.toFloat()
                    var virtualY = CENTRE.toFloat()
                    var lastX = down.position.x
                    var lastY = down.position.y
                    var lastTime = down.uptimeMillis
                    var lastHoldNanos = 0L
                    var locked = false
                    // Smoothed recent motion. The finger's contact patch rolls
                    // a little when it lands; that transient points opposite
                    // to the coming swipe (right before a leftward drag, up
                    // before a downward one). Locking on the raw travel from
                    // touchdown picked that roll up and sent one small wrong
                    // step first. Requiring the recent motion to agree with
                    // the travel rejects the roll without adding a dead zone.
                    var recentDx = 0f
                    var recentDy = 0f
                    val filter = SwipeFilter(swipeGuard)
                    val lockPx = LOCK_DISTANCE.toPx()

                    // Always start from the middle of the virtual surface.
                    onTouch(CENTRE, CENTRE, TouchPhase.PRESS)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        val stepX = change.position.x - lastX
                        val stepY = change.position.y - lastY
                        recentDx = recentDx * 0.55f + stepX * 0.45f
                        recentDy = recentDy * 0.55f + stepY * 0.45f

                        if (!locked) {
                            val rawDx = change.position.x - down.position.x
                            val rawDy = change.position.y - down.position.y
                            val horizontalNow = abs(rawDx) >= abs(rawDy)
                            val travel = if (horizontalNow) rawDx else rawDy
                            val recent = if (horizontalNow) recentDx else recentDy

                            // Commit only when the finger has crossed the
                            // distance threshold *and* is still moving the
                            // same way it travelled: a roll that is already
                            // reversing cannot lock the swipe.
                            if (abs(travel) < lockPx ||
                                travel.sign == 0f ||
                                travel.sign != recent.sign ||
                                abs(recent) < 0.5f
                            ) {
                                if (change.changedToUp()) break
                                change.consume()
                                continue
                            }
                            locked = true
                            // Start from the centre and hand the filter a
                            // short nudge in the settled direction instead of
                            // the raw offset. The first sample therefore
                            // always points forward, whatever the finger did
                            // while the direction was being decided.
                            virtualX = CENTRE.toFloat()
                            virtualY = CENTRE.toFloat()
                            if (horizontalNow) {
                                virtualX += travel.sign * INITIAL_STEP
                            } else {
                                virtualY += travel.sign * INITIAL_STEP
                            }
                        } else {
                            val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L) / 1000f
                            val fracX = stepX / referencePx
                            val fracY = stepY / referencePx
                            val gain =
                                TouchAcceleration.gain(hypot(fracX, fracY) / dt) * SENSITIVITY

                            virtualX = (virtualX + fracX * 1000f * gain).coerceIn(0f, 1000f)
                            virtualY = (virtualY + fracY * 1000f * gain).coerceIn(0f, 1000f)
                        }

                        lastX = change.position.x
                        lastY = change.position.y
                        lastTime = change.uptimeMillis

                        val point = filter.onMove(virtualX.roundToInt(), virtualY.roundToInt())
                        val now = System.nanoTime()
                        if (point != null && now - lastHoldNanos >= HOLD_INTERVAL_NS) {
                            lastHoldNanos = now
                            onTouch(point.x, point.y, TouchPhase.HOLD)
                        }

                        if (change.changedToUp()) break
                        change.consume()
                    }

                    val end = filter.onEnd(virtualX.roundToInt(), virtualY.roundToInt())
                    onTouch(end.x, end.y, TouchPhase.RELEASE)
                    pressed = false
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

private data class TouchPoint(val x: Int, val y: Int)

/**
 * Turns accumulated virtual-pad positions into a clean swipe path.
 *
 * The first meaningful movement locks an axis and direction. Afterwards only
 * forward steps are forwarded; a reversal has to travel [REVERSE_THRESHOLD]
 * back before it is accepted, and the release uses the last accepted point.
 */
private class SwipeFilter(
    private val guard: Boolean,
    private val startX: Int = CENTRE,
    private val startY: Int = CENTRE,
) {
    private enum class Axis { HORIZONTAL, VERTICAL }

    private var lastX = startX
    private var lastY = startY
    private var axis: Axis? = null
    private var direction = 0

    fun onMove(x: Int, y: Int): TouchPoint? {
        if (!guard) {
            lastX = x
            lastY = y
            return TouchPoint(x, y)
        }

        val locked = axis
        if (locked == null) {
            val dx = x - startX
            val dy = y - startY
            if (dx * dx + dy * dy < START_THRESHOLD * START_THRESHOLD) return null
            val horizontal = abs(dx) >= abs(dy)
            axis = if (horizontal) Axis.HORIZONTAL else Axis.VERTICAL
            direction = if (horizontal) dx.sign else dy.sign
            lastX = x
            lastY = y
            return TouchPoint(x, y)
        }

        val delta = if (locked == Axis.HORIZONTAL) x - lastX else y - lastY
        val forward = delta * direction
        val distance = abs(delta)

        if (forward > 0) {
            if (distance < STEP_THRESHOLD) return null
            lastX = x
            lastY = y
            return TouchPoint(x, y)
        }

        // Ignore a small backward wobble. A real reversal must cross the
        // wider threshold, after which its direction becomes the new one.
        if (-forward < REVERSE_THRESHOLD) return null
        direction = -direction
        lastX = x
        lastY = y
        return TouchPoint(x, y)
    }

    fun onEnd(rawX: Int, rawY: Int): TouchPoint =
        if (guard) TouchPoint(lastX, lastY) else TouchPoint(rawX, rawY)
}

/** Virtual surface is 0..1000 in both axes; every gesture starts at its centre. */
private const val CENTRE = 500

/** How far the finger must travel before a touch becomes a swipe. */
private val COMMIT_DISTANCE = 14.dp

/**
 * Everything the accelerated path produces is scaled down by this: the pad
 * covers a much larger virtual surface than the finger crosses, and the user
 * asked for visibly calmer travel. Raise back toward 1.0 to speed things up.
 */
private const val SENSITIVITY = 0.55f

/**
 * Finger travel needed to settle the swipe direction before the first sample
 * is forwarded. Larger than the commit distance: inside this zone a light
 * flick produces nothing at all rather than a stray first step.
 */
private val LOCK_DISTANCE = 20.dp

/**
 * First virtual step once the direction is settled. It has to clear
 * [START_THRESHOLD], otherwise the filter would drop the sample and the swipe
 * would begin with a visible pause.
 */
private const val INITIAL_STEP = 32f

/** Minimum accumulated movement before an axis is locked. */
private const val START_THRESHOLD = 30

/** Minimum step between forwarded HOLD samples. */
private const val STEP_THRESHOLD = 10

/** Deliberate pull-back needed to reverse an already locked swipe. */
private const val REVERSE_THRESHOLD = 110

/** tvOS does not need more than ~80 samples per second. */
private const val HOLD_INTERVAL_NS = 12_000_000L
