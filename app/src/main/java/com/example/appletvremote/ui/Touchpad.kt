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

/**
 * Apple-style touch surface.
 *
 * A tap is a real SELECT press, a long hold opens the contextual menu, and a
 * drag is forwarded as PRESS/HOLD/RELEASE samples.
 *
 * Taps are separated from swipes by Android's own touch slop. Everything the
 * finger did before that point is a dead zone and is not forwarded, so the
 * first sample after PRESS is a clean step instead of a velocity spike. The
 * swipe itself is driven by [SwipeTracker], which explains how the roll of a
 * thumb at the very start of a gesture is kept from jerking the focus the
 * wrong way, and how the finger is lifted at an edge so a flick keeps its
 * speed.
 *
 * Every gesture starts at the **centre** of the virtual 0..1000 surface, not
 * under the finger: tvOS reads the movement between samples, and starting near
 * an edge used to make one or two directions impossible. Finger travel is
 * scaled up by [GAIN_UNITS_PER_DP], which gives the pad the physical scale of
 * a real remote: about 26 mm of travel across the whole surface.
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

                    var tracker: SwipeTracker? = null
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
                        // forwarded.
                        val swipe = SwipeTracker(
                            unitsPerPx = unitsPerPx,
                            armConfirmPx = ARM_CONFIRM_DP.dp.toPx(),
                            armWindowNs = ARM_WINDOW_NS,
                            adoptMinPx = ARM_MIN_DP.dp.toPx(),
                            reverseConfirmPx = REVERSE_CONFIRM_DP.dp.toPx(),
                            holdIntervalNs = HOLD_INTERVAL_NS,
                            minStepUnits = minStep,
                        )
                        tracker = swipe
                        swipe.begin(start.position.x, start.position.y, System.nanoTime())
                            .let { onTouch(it.x, it.y, it.phase) }

                        while (true) {
                            val current = awaitPointerEvent()
                                .changes
                                .firstOrNull { it.id == down.id }
                                ?: break

                            val sample = swipe.move(
                                current.position.x,
                                current.position.y,
                                System.nanoTime(),
                            )
                            if (sample != null) {
                                onTouch(sample.x, sample.y, sample.phase)
                                if (sample.phase == TouchPhase.RELEASE) {
                                    // Sliding off the edge: lift there and stop
                                    // sending samples, so the TV gets a release
                                    // that is still moving and the flick
                                    // carries on instead of dying. The rest of
                                    // the gesture belongs to the pad, but there
                                    // is no finger left to follow.
                                    if (!current.changedToUp()) {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val up = event.changes
                                                .firstOrNull { it.id == down.id }
                                                ?: break
                                            if (up.changedToUp()) break
                                            up.consume()
                                        }
                                    }
                                    return@awaitEachGesture
                                }
                            }

                            if (current.changedToUp()) break
                            current.consume()
                        }

                        // Release where the finger actually stopped.
                        swipe.end()?.let { onTouch(it.x, it.y, it.phase) }
                    } finally {
                        // The gesture was cancelled mid-swipe: never leave the
                        // TV with a finger held down.
                        tracker?.let { swipe ->
                            if (swipe.isTouching) {
                                swipe.end()?.let { onTouch(it.x, it.y, it.phase) }
                            }
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

/**
 * Virtual units per dp of finger travel. Clicker measured that 1000 units on a
 * real remote correspond to roughly 33 mm of finger travel (about 208 dp), so
 * one screen-independent pixel of travel is worth about five units. Six is a
 * little more generous than that, which keeps a short slow swipe worth a full
 * focus step now that the first few millimetres are held back while the
 * direction is confirmed.
 */
private const val GAIN_UNITS_PER_DP = 6f

/**
 * Net travel that settles the direction of a swipe on its own, in dp. A normal
 * swipe crosses this within a few tens of milliseconds.
 */
private const val ARM_CONFIRM_DP = 9f

/**
 * How long the direction may stay undecided, in nanoseconds. A slow swipe
 * sends its first sample when this runs out, provided the net travel has
 * already reached [ARM_MIN_DP]; waiting any longer would make the pad feel
 * laggy.
 */
private const val ARM_WINDOW_NS = 70_000_000L

/** Smallest net travel worth acting on once [ARM_WINDOW_NS] has passed. */
private const val ARM_MIN_DP = 2.5f

/**
 * Travel against the chosen direction that counts as a real reversal rather
 * than a wobble, in dp.
 */
private const val REVERSE_CONFIRM_DP = 7f

/**
 * Smoothest movement that is still forwarded when the jitter filter is on.
 * Below this the sample is not dropped, only delayed: the next sample carries
 * the accumulated distance.
 */
private const val MIN_STEP_UNITS = 4f

/** ~62 samples per second, matching pyatv's TOUCHPAD_DELAY_MS. */
private const val HOLD_INTERVAL_NS = 16_000_000L
