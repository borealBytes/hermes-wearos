package dev.hermes.wearos.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass

// STALE-LIFT GESTURE GUARD (issue #24, second wave — lane-landing saga).
//
// The same finger that COMMITTED a dial lane can still be resting on the spot where a
// sheet/overlay appears under it when it lifts. The 350 ms dismiss guard (staleSheetLift)
// only protects scrim-tap dismissal — the ghost lift also lands on ITEMS inside the
// overlay (trace: CommandsOverlay self-dismisses ~0.8 s after opening because the lift
// clicks the top slash item and runCommand executes it). A time window alone can't fix
// that without blinding real fast taps, so this guard keys on GESTURE IDENTITY instead:
// a pointer stream whose DOWN happened before the sheet opened (or within 400 ms after
// it) is an in-flight continuation of the commit drag — we consume it whole at the
// Initial pass so no child `clickable` ever sees it. A fresh tap (DOWN after the window)
// passes untouched — the keyboard-parity rule (a tap always does what the rest of the
// app says it does) is preserved.
fun Modifier.staleGestureGuard(sheetsOpenedAtMs: LongArray): Modifier =
    pointerInput(sheetsOpenedAtMs) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.uptimeMillis - sheetsOpenedAtMs[0] < 400L) {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
