package com.example.appletvremote.ui

import com.example.appletvremote.protocol.companion.TouchPhase
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One sample of the virtual finger, in the Companion protocol's 0..1000 space. */
data class SwipeSample(val x: Int, val y: Int, val phase: TouchPhase)

/**
 * Turns finger travel on the phone's pad, in pixels, into the touch samples the
 * Apple TV expects.
 *
 * The beginning of a swipe is the hard part. A thumb does not start moving the
 * way it ends: while it flattens against the glass its contact patch first
 * slides a few millimetres **against** the intended direction, and only then
 * does the real swipe begin. Forwarded straight through, tvOS moves the focus
 * the wrong way for a row or two and then back — the jerk that shows up on
 * short swipes up and left, where there is usually room to move backwards.
 *
 * So the swipe is not forwarded the moment the finger leaves the tap dead
 * zone. The press lands in the middle of the TV's surface (harmless on its
 * own) and the travel is held back:
 *
 *  - **Arming.** Nothing is sent until the net travel from the commit point is
 *    either [armConfirmPx], or at least [adoptMinPx] once [armWindowNs] has
 *    passed — and in both cases only while the latest sample still moves the
 *    way the net points. Only the *net* displacement is then forwarded, so the
 *    roll at the start cancels itself out and the first thing tvOS sees is the
 *    real direction. A finger that turns around therefore never sends a sample
 *    backwards, however slow it is.
 *  - **Direction lock.** Afterwards, movement against the chosen direction is
 *    withheld and counted, so a wobble mid-swipe cannot drag the focus back. A
 *    deliberate reversal — more than [reverseConfirmPx] against the direction —
 *    flips the lock and gives the withheld travel back.
 *  - **Sampling.** At most one sample per [holdIntervalNs], and only once the
 *    accepted point has moved [minStepUnits] from the last one, so a slow swipe
 *    is neither swallowed nor turned into a burst of tiny jumps. The skipped
 *    distance is accumulated, not discarded.
 *
 * Reaching an edge sends RELEASE at the edge and ends the stream, as a finger
 * sliding off the real remote would: pinning it there made tvOS see the finger
 * stop dead and lift with no velocity, which jerked focus back.
 */
class SwipeTracker(
    private val unitsPerPx: Float,
    private val armConfirmPx: Float,
    private val armWindowNs: Long,
    private val adoptMinPx: Float,
    private val reverseConfirmPx: Float,
    private val holdIntervalNs: Long,
    private val minStepUnits: Float,
) {
    private var touching = false
    private var released = false

    // The virtual finger, in protocol units.
    private var sendX = CENTRE.toFloat()
    private var sendY = CENTRE.toFloat()
    private var emittedX = CENTRE.toFloat()
    private var emittedY = CENTRE.toFloat()
    private var lastEmitNs = 0L

    // The last finger sample, in pixels.
    private var fingerX = 0f
    private var fingerY = 0f

    // Arming: the commit point and when the window opened.
    private var arming = false
    private var armX = 0f
    private var armY = 0f
    private var armStartNs = 0L

    // Locked direction as a unit vector, and travel withheld against it.
    private var dirX = 0f
    private var dirY = 0f
    private var withheldPx = 0f

    /** Whether the TV still holds a finger down, so a RELEASE is owed. */
    val isTouching: Boolean get() = touching && !released

    /**
     * The finger has committed to a swipe at [x],[y] px, after the tap dead
     * zone. Returns the PRESS that puts the virtual finger in the middle of
     * the surface.
     */
    fun begin(x: Float, y: Float, nowNs: Long): SwipeSample {
        touching = true
        released = false
        fingerX = x
        fingerY = y
        armX = x
        armY = y
        armStartNs = nowNs
        arming = true
        dirX = 0f
        dirY = 0f
        withheldPx = 0f
        lastEmitNs = nowNs
        return SwipeSample(CENTRE, CENTRE, TouchPhase.PRESS)
    }

    /** Feed one finger position. Returns a sample to send, if any. */
    fun move(x: Float, y: Float, nowNs: Long): SwipeSample? {
        if (!isTouching) return null
        val dx = x - fingerX
        val dy = y - fingerY
        fingerX = x
        fingerY = y
        return if (arming) arm(x, y, dx, dy, nowNs) else follow(dx, dy, nowNs)
    }

    /**
     * The finger lifted. Returns the RELEASE to send, or null when the touch
     * already ended — at an edge, or because it never started.
     */
    fun end(): SwipeSample? {
        if (!isTouching) return null
        released = true
        touching = false
        return SwipeSample(
            sendX.coerceIn(0f, RANGE_UNITS).roundToInt(),
            sendY.coerceIn(0f, RANGE_UNITS).roundToInt(),
            TouchPhase.RELEASE,
        )
    }

    /** Hold the travel back until the direction is trustworthy. */
    private fun arm(x: Float, y: Float, dx: Float, dy: Float, nowNs: Long): SwipeSample? {
        val netX = x - armX
        val netY = y - armY
        val net = hypot(netX, netY)
        val decided = net >= armConfirmPx || nowNs - armStartNs >= armWindowNs
        // The latest sample has to point the same way as the net: while the
        // thumb is still rolling backwards the two disagree, and that is
        // exactly the moment not to trust the net. A repeated position (the
        // event that ends a still finger) says nothing either way.
        val settled = (dx == 0f && dy == 0f) || dx * netX + dy * netY > 0f
        if (!decided || !settled || net < adoptMinPx) return null

        arming = false
        dirX = netX / net
        dirY = netY / net
        sendX += netX * unitsPerPx
        sendY += netY * unitsPerPx
        return publish(nowNs, force = true)
    }

    /** Follow the finger along the locked direction. */
    private fun follow(dx: Float, dy: Float, nowNs: Long): SwipeSample? {
        val along = dx * dirX + dy * dirY
        var accepted = 0f
        if (along >= 0f) {
            withheldPx = 0f
            accepted = along
        } else {
            withheldPx += -along
            if (withheldPx >= reverseConfirmPx) {
                // A real reversal, not a wobble: turn the lock and give back
                // the travel that was withheld while it looked like one.
                dirX = -dirX
                dirY = -dirY
                accepted = withheldPx
                withheldPx = 0f
            }
        }
        // Perpendicular travel always passes, so curves and diagonals keep
        // working instead of being flattened onto one axis.
        val perp = -dx * dirY + dy * dirX
        val moveX = (accepted * dirX - perp * dirY) * unitsPerPx
        val moveY = (accepted * dirY + perp * dirX) * unitsPerPx
        if (moveX == 0f && moveY == 0f) return null
        sendX += moveX
        sendY += moveY
        return publish(nowNs, force = false)
    }

    /** Clamp to the surface, lift at an edge, and throttle the samples. */
    private fun publish(nowNs: Long, force: Boolean): SwipeSample? {
        if (sendX < 0f || sendX > RANGE_UNITS || sendY < 0f || sendY > RANGE_UNITS) {
            sendX = sendX.coerceIn(0f, RANGE_UNITS)
            sendY = sendY.coerceIn(0f, RANGE_UNITS)
            released = true
            return SwipeSample(sendX.roundToInt(), sendY.roundToInt(), TouchPhase.RELEASE)
        }
        if (!force && nowNs - lastEmitNs < holdIntervalNs) return null
        val moved = hypot(sendX - emittedX, sendY - emittedY)
        if (moved == 0f || moved < minStepUnits) return null
        emittedX = sendX
        emittedY = sendY
        lastEmitNs = nowNs
        return SwipeSample(sendX.roundToInt(), sendY.roundToInt(), TouchPhase.HOLD)
    }

    private companion object {
        /** Side of the virtual surface, in protocol units. */
        const val RANGE_UNITS = 1000f

        /** Where every gesture starts: the middle of the virtual surface. */
        const val CENTRE = 500
    }
}
