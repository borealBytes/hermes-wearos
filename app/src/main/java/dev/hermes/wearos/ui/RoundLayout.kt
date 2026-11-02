package dev.hermes.wearos.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hermes.wearos.domain.RoundScreen

/**
 * Round-screen plumbing for Wear Compose **1.4.1** (owner ask #5). The 1.5 `Scaffold` /
 * `ROUND_SCAFFOLD_INSETS` symbols don't exist in 1.4.1 (verified against the shipped artifacts),
 * so this is the "windowInsetsPadding equivalent" the task allows: one small source of truth for
 * the safe circle — status/clock top, chin bottom, ~20 dp horizontal falloff — consumed by every
 * screen (chat / chats / settings / sessions / dialogs / orb bar). Pure math lives in
 * [RoundScreen] (unit-tested); this file only translates dp → insets for composables.
 */

/** Is this a round viewport? (Wear emulator + Ultra are; square preview devices aren't.) */
@Composable
fun isRoundScreen(): Boolean = LocalConfiguration.current.isScreenRound
// (The old bit trick `(screenLayout and 0x03) == 0x02` was WRONG — round lives under mask 0x0C,
// value 0x08 — and silently reported FALSE on the owner's SM-L705U, so every rim rule in the app
// quietly ran in square mode. Proven 2026-10-30 by a q-lift log showing listTopPad = 0. The
// platform's own `isScreenRound` cannot drift.)

@Composable
fun roundInsets(): RoundScreen.Insets = RoundScreen.insetsFor(isRoundScreen())

/** The screen's safe circle radius in dp (half the smaller dimension). */
@Composable
fun screenRadiusDp(): Float {
    val cfg = LocalConfiguration.current
    return RoundScreen.screenRadiusDp(minOf(cfg.screenWidthDp.toFloat(), cfg.screenHeightDp.toFloat()))
}

/** Insets padding = the safe circle: keeps scrollables' first/last items out of clock + chin. */
@Composable
fun Modifier.roundSafe(): Modifier {
    val i = roundInsets()
    return this.windowInsetsPadding(
        WindowInsets(top = i.topDp.dp, bottom = i.bottomDp.dp, left = i.horizontalDp.dp, right = i.horizontalDp.dp),
    )
}

/**
 * BATCH-4 #6: dp of clearance a scrolling column needs at the BOTTOM so its last row's
 * bottom-LEFT corner reaches the meat of the circle (rim ∩ the content gutter, no orb).
 * Square faces get just [bottomExtraDp] (the rim rule is a round-glass concern).
 *
 * Uses the VIEW's display metrics, not LocalConfiguration: inside Dialog windows the
 * ComposeLocalConfiguration reports the dialog window's (sometimes degenerate) bounds, and
 * these helpers run in dialogs too (agent/model/approval pickers) — the physical screen is
 * what the rim rule needs.
 */
@Composable
fun circleSafeBottomDp(contentLeftDp: Float, bottomExtraDp: Float = 8f): Float {
    val view = androidx.compose.ui.platform.LocalView.current
    val dm = view.resources.displayMetrics
    val density = dm.density
    val pad = dev.hermes.wearos.domain.BottomLayout.circleSafeBottomPad(
        viewportW = dm.widthPixels / density,
        viewportH = dm.heightPixels / density,
        round = isRoundScreen(),
        contentLeftPx = contentLeftDp,
        orbTopPx = dm.heightPixels / density,
        marginPx = bottomExtraDp,
    )
    android.util.Log.d(
        "HermesWear", "circleSafeBottomDp=${pad.toInt()}dp via ${dm.widthPixels}x${dm.heightPixels}" +
            "@$density (cfg=${androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp}" +
            "x${androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp})",
    )
    return pad
}

/**
 * ScalingLazyColumn contentPadding: items REST below the clock and ABOVE the chin (and the SLC's
 * own responsive scaling handles the edge falloff); horizontal gutter tops up the rows' internal
 * ~10 dp padding to the safe-circle target. [bottomExtraDp] reserves room for the orb bar on chat.
 * [bottomOverrideDp] (Layout D): when set it REPLACES the chin-based bottom entirely — the chat
 * passes the measured bottom-cluster top edge (BottomLayout.transcriptBottomPad), which already
 * dwarfs the chin inset.
 */
@Composable
fun roundListPadding(
    bottomExtraDp: Float = 8f,
    topExtraDp: Float = 0f,
    bottomOverrideDp: Dp? = null,
    topOverrideDp: Dp? = null,
): PaddingValues {
    val i = roundInsets()
    val gutter = (i.horizontalDp - 10f).coerceAtLeast(0f)
    // BATCH-4 #6 (owner: *"top/bottom buffer padding on EVERY list — any last row must scroll
    // into the meat of the circle"*): the chin inset alone leaves the LAST row's bottom-left
    // corner inside the bezel shave-zone on round glass. Fold in the same rim math the chat
    // uses (BottomLayout.circleSafeBottomPad with orbTop = full height — these lists have no
    // orb), in DP space so it is scale-invariant. [bottomOverrideDp] (chat's cluster pad)
    // already IS that computation, so overrides pass through untouched.
    val bottomSafe = maxOf(i.bottomDp + bottomExtraDp, circleSafeBottomDp(gutter, bottomExtraDp))
    return PaddingValues(
        top = topOverrideDp ?: (i.topDp + topExtraDp).dp,
        bottom = bottomOverrideDp ?: bottomSafe.dp,
        start = gutter.dp,
        end = gutter.dp,
    )
}

/**
 * Lift for the orb bar's SIDE buttons so the bar follows the circle's arc (Now-in-Edge) instead of
 * a square bottom line where the chin clip eats the corners.
 */
@Composable
fun arcLiftDp(offsetFromCenterDp: Float): Float {
    if (!isRoundScreen()) return 0f
    return RoundScreen.arcLiftDp(screenRadiusDp(), offsetFromCenterDp)
}
