package dev.hermes.wearos.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.MutableState
import androidx.compose.ui.input.pointer.pointerInput
import dev.hermes.wearos.domain.EdgeScroll

/**
 * THE EDGE-CIRCLE GESTURE (batch-4 #7, owner: *"finger circling the screen edge CW/CCW
 * scrolls… chat first as the pattern, then every surface"*). domain/EdgeScroll owns the math
 * (why it is not the library's edgeRotate: compose-foundation 1.4.1 has no touch-edge API,
 * verified against the shipped jar).
 *
 * TWO on-device rules this implementation encodes the hard way:
 *  1. wear-compose 1.4.1 cannot be scrolled MID-TOUCH by an outside gesture: scrollBy returns
 *     used=0 while the finger is down (the SLC's own touch channel owns scrolling until UP) and
 *     animateScrollBy is aborted by the list's live-drag bookkeeping — both measured with
 *     per-step logs on the SM-L705U. So the circle BANKS its travel and applies it as ONE
 *     animateScrollBy on RELEASE (measured reliable), like a rotary detent landing its steps.
 *  2. LOCK on clear angular velocity ([EdgeScroll.LOCK_DELTA_DEG]/event): a straight drag from
 *     the rim (even sliding along the curve) drifts well under it and is handed to the list
 *     untouched; a circle locks by its second event and consumes every move from then on, so
 *     the list underneath never drags along with the circle. Events are read on
 *     [PointerEventPass.Initial] so the claim happens before the child scrollable reacts.
 *
 * NOT suspend — PointerInputScope is not a coroutine scope and the AwaitPointerEventScope
 * restricts suspension to itself — so callers dispatch the scroll on their own
 * `rememberCoroutineScope`.
 */
fun Modifier.edgeCircleScroll(
    enabled: Boolean = true,
    /** Set while the gesture OWNS the touch (down-in-ring → up); informational for call sites
     *  that want to suppress sibling behaviour during a circle. */
    driving: MutableState<Boolean>? = null,
    /** Gear for the surface's density (Sessions rides 2f, owner #28): see EdgeScroll.scrollPxFor. */
    speed: Float = 1f,
    /** Fires ONCE on release with the banked travel: POSITIVE px = screen-CLOCKWISE = scroll
     *  FORWARD (down the list, toward newest); NEGATIVE = counter-clockwise = BACK through
     *  history. Sign verified end-to-end on the SM-L705U (chat + settings + dialogs). The
     *  caller feeds it straight to its scroll surface (same sign convention as ScrollBy). */
    onScrollEnd: (totalPx: Float) -> Unit,
): Modifier = pointerInput(enabled, speed) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        if (!EdgeScroll.inRing(down.position.x, down.position.y, w, h)) {
            return@awaitEachGesture // not ours: downstream lists handle this gesture whole
        }
        driving?.value = true
        var prev = EdgeScroll.angleDeg(down.position.x, down.position.y, w, h)
        val dDown = EdgeScroll.distFromCenter(down.position.x, down.position.y, w, h)
        var remainder = 0f
        var locked = false
        var acc = 0f
        // STROKE TELEMETRY (#28): a locked stroke that banks ~nothing looks identical to a dead
        // gesture from the wrist. Counting events/degrees separates "gesture never locked" from
        // "the math round-tripped" — one log line per honest failure mode.
        var nEvents = 0
        var degAcc = 0f
        try {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val change = ev.changes.firstOrNull { it.pressed } ?: break
                val ang = EdgeScroll.angleDeg(change.position.x, change.position.y, w, h)
                val delta = EdgeScroll.angleDeltaDeg(prev, ang)
                prev = ang
                nEvents++
                degAcc += kotlin.math.abs(delta)
                val drift = EdgeScroll.radialDrift(
                    dDown,
                    EdgeScroll.distFromCenter(change.position.x, change.position.y, w, h),
                    w, h,
                )
                if (!locked && kotlin.math.abs(delta) >= EdgeScroll.LOCK_DELTA_DEG &&
                    drift <= EdgeScroll.LOCK_MAX_DRIFT_RATIO
                ) {
                    // A real circle: angular velocity AND a held radius. Anything else — above
                    // all a rim-to-center SWIPE, whose angle explodes near the middle — stays
                    // unconsumed so the list drags it normally (#28).
                    locked = true // from here we consume and the list never drags
                }
                if (locked) {
                    change.consume()
                    val step = EdgeScroll.scrollPxFor(ang - delta, ang, remainder, w, h, speed = speed)
                    remainder = step.remainderDeg
                    acc += step.px
                }
            }
        } finally {
            driving?.value = false
        }
        // THE RELEASE-APPLY RULE (wear-compose 1.4.1, measured on-device): mid-touch scroll
        // injection is structurally dead — scrollBy returns used=0 while the finger is down and
        // animateScrollBy reports the full distance yet lands only once the touch channel closes.
        // The circle therefore banks its travel and the caller lands it on release, like a
        // rotary detent: SLC lists via [edgeScrollWalk] (live, center-anchored steps), plain
        // ScrollStates by scrollBy (their drag never starts — we consumed its moves).
        if (locked && acc != 0f) {
            android.util.Log.d("HermesWear", "edge circle release: ${acc.toInt()}px")
            onScrollEnd(acc)
        } else if (locked) {
            // A LOCKED stroke that banked ~0px is the "bezel drag does nothing" complaint in
            // one log line: the finger circled, the math round-tripped to nothing (sign flips
            // / aliasing). Without this the wrist says "dead", the logs say nothing happened.
            android.util.Log.d("HermesWear", "edge circle release: banked 0 (locked, ${nEvents} events, ${degAcc.toInt()}deg)")
        }
    }
}

/**
 * Land an edge-circle's [totalPx] on a ScalingLazyListState LIVE mid/near-touch: walk the
 * distance in ≤120px ABSOLUTE steps in the state's OWN offset space — `centerItemScrollOffset`
 * seeded once, re-issued against the same center item per frame (last write wins; stale
 * layoutInfo reads cannot trick it, and wear clamps honestly at the list ends).
 *
 * Why not layoutInfo.visibleItemsInfo offsets (measured on the SM-L705U): they are the SIGN
 * MIRROR of scrollToItem's offset space whenever the anchor item rides above the viewport top
 * (the chat question anchor + its content padding do exactly that) — every step landed the
 * wrong way the moment position crossed zero. centerItemScrollOffset is the same number
 * scrollToItem consumes, and POSITIVE totalPx = forward, matching ScrollState.scrollBy.
 */
suspend fun androidx.wear.compose.foundation.lazy.ScalingLazyListState.edgeScrollWalk(totalPx: Float) {
    val idx = centerItemIndex
    val base = centerItemScrollOffset
    val dir = if (totalPx >= 0f) 1f else -1f
    val dist = kotlin.math.abs(totalPx)
    var done = 0f
    while (done < dist) {
        done = (done + 120f).coerceAtMost(dist)
        // Forward (POSITIVE px) = the center item rides UP = its center offset GROWS (sign
        // verified end-to-end on-device: tail+positive clamps, mid-list positive advances).
        scrollToItem(idx, (base + dir * done).toInt())
        androidx.compose.runtime.withFrameNanos { }
    }
}
