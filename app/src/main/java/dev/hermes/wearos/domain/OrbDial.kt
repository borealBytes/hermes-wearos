package dev.hermes.wearos.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One thing the orb dial can do (SPEC §12.1 D1–D8). The enum is the SETTINGS VOCABULARY too:
 * `id` is what persists in DataStore (`OrbDial.encode/decode`), `glyph` is what the ring draws,
 * `label` is what the Settings ▸ Orb rows and the highlight caption say.
 *
 * `None` is a real choice, not a gap: an owner who keeps opening `model` by accident can blank a
 * direction, and a blank direction releases to nothing (it does NOT fall back to the tap).
 *
 * The 4:30 lane used to be `voice_notes` (a §13.4 branch-2 stub nobody ever fired). The owner's
 * take: "I've never used the notes — make that the model selector". Old saves still carry
 * `voice_notes`; [OrbDial.decode] treats it as an unknown id and hands that slot the new default,
 * which is exactly the migration we want.
 *
 * 3:00 followed in the same batch: `record_hi` was the OTHER never-shipped stub, and the owner
 * asked for a straight line from the dial to Settings ("it's a little tough to get to settings").
 * So defaults send 3:00 → [Lane.Settings]; `record_hi` stays a first-class lane for anyone who
 * remaps it — and old saves that name it explicitly keep it, honoring a choice that was made.
 */
enum class Lane(val id: String, val glyph: String, val label: String) {
    None("none", "·", "off"),
    Keyboard("keyboard", "⌨", "keyboard"),
    Mute("mute", "🔊", "mute"),
    RecordHi("record_hi", "🎙", "record"),
    Model("model", "🧠", "model"),
    Commands("commands", "⌘", "commands"),
    Chats("chats", "💬", "chats"),
    Sessions("sessions", "≡", "sessions"),
    Agents("agents", "👤", "agents"),
    /**
     * Per-chat approval mode (owner 2026-10-29): release → the approvals sheet for THIS chat
     * (inherit/manual/smart/off, domain/ApprovalMode). One more option than dial directions —
     * the owner maps whichever direction he wants; Settings ▸ Orb lists every lane.
     */
    Approvals("approvals", "✅", "approvals"),
    Settings("settings", "⚙", "settings");

    companion object {
        fun fromId(id: String): Lane? = entries.firstOrNull { it.id == id.trim() }
    }
}

/**
 * THE ORB DIAL (owner batch 3: "get all but the orb button off of the main screen … eight
 * directions you can drag it for different options"). One gesture lane on the talk orb replaces the
 * agent / sessions / mute satellites and the two header chips: drag out of the disc, let go, the
 * lane fires. The ring icons are drawn FARTHER OUT than the activation gate ([ICON_RING_REF] vs
 * [ACTIVATE_REF]) so you are always dragging *toward* an icon you can see, and the ring appears in
 * well under 100 ms — once the map is learned the whole gesture is a blind flick, which is exactly
 * the owner's "it should be very fast … the icon pops up and you don't even look at it".
 *
 * Slot 0 is 12:00 — the drag-up → keyboard editor that already ships, same direction, now one lane
 * among eight — and slots run CLOCKWISE in 45° wedges: 1 = 1:30, 2 = 3:00, 3 = 4:30, 4 = 6:00,
 * 5 = 7:30, 6 = 9:00, 7 = 10:30. The owner's clock-face model is stated as an INDEX LIST
 * (`AppSettings.orbSlots()`) so every direction is remappable in Settings ▸ Orb.
 *
 * GEOMETRY is unit-tested against the real face (Galaxy Watch Ultra, 480×480 @ 340dpi — numerically
 * identical to `BottomLayout.REF`, so 1 ref unit = 1 screen px on the owner's watch). The orb is
 * grounded 15 ref-px off the bottom edge (`BottomLayout.ORB_DY` / `ORB_R`), so the LOWER wedges have
 * no room for their icons: the owner's locked answer is the CONTEXTUAL RISE — the disc slides
 * toward face center ONLY once the drag enters the lower arc ([needsRise]), making 4:30 / 6:00 /
 * 7:30 reachable while every other direction never moves the orb at all.
 *
 * Pure: viewport px + the 480→viewport scale in, lanes out. No android.* imports — JVM-tested
 * (OrbDialTest).
 */
object OrbDial {
    const val SLOT_COUNT = 8
    const val SLOT_DEG = 45f

    /**
     * Icon centers sit this far from the orb center (ref px). Chosen so the ring clears the
     * fingertip for the whole learning phase (activation is at 51) and still fits the face: with
     * the orb at y=401 the 12:00 icon lands at y=283 and the 3:00/9:00 icons at x=358/122 — all
     * inside the 480 circle; the lower three only after [risenCenterY] moves the disc up.
     */
    const val ICON_RING_REF = 118f

    /** One ring icon's radius (ref px) — 52 ref ≈ 24 dp across the safe circle, the tap target it
     *  would need if it were tappable; here it is the highlight circle's size. */
    const val ICON_R_REF = 26f

    /**
     * Drag distance (ref px) that turns "finger on the orb" into "dragging a lane". 51 ref ≈ 24 dp
     * at 340dpi and ≈0.8× the orb's own radius: a deliberate flick arms instantly, a tap-with-jitter
     * (measured ≤7 px in the walk-through bug) never does.
     */
    const val ACTIVATE_REF = 51f

    /**
     * Lateral travel (ref px) per detent in SPIN mode (see [DialSpin]) — ≈13 mm at 340 dpi,
     * deliberately smaller than the ~90 px arc between icons so a thumb sweep of a comfortable
     * length clicks through several slots, and deliberate enough that an aim-drag's incidental
     * sideways lean never engages the spin.
     */
    const val SPIN_STEP_REF = 34f

    /** Spin only arms while the finger aims this far from 12:00 — the owner's exact hand posture
     *  ("slide it up, then you can go right or left"); side/bottom drags stay pure aim. */
    const val SPIN_UP_ARC_DEG = 65f

    /** Lower-arc bounds that trigger the contextual rise: the 4:30 / 6:00 / 7:30 wedges. */
    const val RISE_FROM_DEG = 112.5f
    const val RISE_TO_DEG = 247.5f

    /** Owner's default map, slot 0 → slot 7 (12:00 → 10:30 clockwise), SPEC §12.1. */
    val DEFAULT_SLOTS: List<Lane> = listOf(
        Lane.Keyboard, Lane.Mute, Lane.Settings, Lane.Model,
        Lane.Commands, Lane.Chats, Lane.Sessions, Lane.Agents,
    )

    /** 480-reference → viewport scale from the MIN dimension (same rule as `BottomLayout`). */
    fun scaleFor(viewportMinPx: Float): Float =
        if (viewportMinPx <= 0f) 0f else viewportMinPx / BottomLayout.REF

    /** Drag angle: 0° = straight up, +90° = right, ±180° = down, measured CLOCKWISE in degrees. */
    fun angleOf(dxPx: Float, dyPx: Float): Float =
        Math.toDegrees(atan2(dxPx.toDouble(), -dyPx.toDouble())).toFloat()

    /** Normalized angle in [0, 360). */
    fun normDeg(angleDeg: Float): Float = ((angleDeg % 360f) + 360f) % 360f

    /**
     * Slot for an angle (0 = 12:00, clockwise), each wedge owning ±22.5° around its center —
     * boundaries land between the icons, never on one, so a diagonal is never ambiguous.
     */
    fun slotAt(angleDeg: Float): Int =
        (normDeg(angleDeg) + SLOT_DEG / 2f).let { (it / SLOT_DEG).toInt() } % SLOT_COUNT

    /** Slot read that can never crash on a short/odd saved map. */
    fun slotFor(slots: List<Lane>, slot: Int): Lane {
        val i = ((slot % SLOT_COUNT) + SLOT_COUNT) % SLOT_COUNT
        return slots.getOrNull(i) ?: DEFAULT_SLOTS[i]
    }

    /**
     * The lane this drag vector selects, or null while the finger has not left the activation gate.
     * Angle decides the lane and nothing else — reaching the icon ring is guidance, not a
     * requirement, so a short confident flick commits and a wander that returns inside the gate
     * cancels (the caller checks [isCancel]).
     */
    fun laneAt(slots: List<Lane>, dxPx: Float, dyPx: Float, scale: Float): Lane? {
        if (scale <= 0f || dist(dxPx, dyPx) < ACTIVATE_REF * scale) return null
        return slotFor(slots, slotAt(angleOf(dxPx, dyPx)))
    }

    /** Release inside the activation gate = "I changed my mind", nothing fires. */
    fun isCancel(dxPx: Float, dyPx: Float, scale: Float): Boolean =
        scale <= 0f || dist(dxPx, dyPx) < ACTIVATE_REF * scale

    /** True when this drag belongs to the crowded lower arc → the disc should rise (SPEC §12.1). */
    fun needsRise(dxPx: Float, dyPx: Float, scale: Float): Boolean {
        if (scale <= 0f || dist(dxPx, dyPx) < ACTIVATE_REF * scale) return false
        return normDeg(angleOf(dxPx, dyPx)) in RISE_FROM_DEG..RISE_TO_DEG
    }

    /**
     * Ring icon center for a slot, VIEWPORT px, around the orb center (cx, cy). [phaseDeg]
     * rotates the WHOLE ring (SPIN mode: the icons travel so the chosen slot arrives under the
     * finger) — 0° keeps the dial exactly as aimed.
     */
    fun iconCenter(cxPx: Float, cyPx: Float, slot: Int, scale: Float, phaseDeg: Float = 0f): Pair<Float, Float> {
        val i = ((slot % SLOT_COUNT) + SLOT_COUNT) % SLOT_COUNT
        val rad = Math.toRadians((i * SLOT_DEG + phaseDeg).toDouble())
        val ring = ICON_RING_REF * scale
        return Pair(cxPx + (ring * sin(rad)).toFloat(), cyPx - (ring * cos(rad)).toFloat())
    }

    /** Fold any angle to (−180, 180] — the shortest signed way between two ring positions. */
    fun normDeg180(deg: Float): Float {
        var d = normDeg(deg)
        if (d > 180f) d -= 360f
        return d
    }

    /** How far [angleDeg] sits from the center of a wedge, 0 = dead on, symmetric ≤180. */
    fun wedgeDist(angleDeg: Float, centerDeg: Float): Float = abs(normDeg180(angleDeg - centerDeg))

    /** Risen orb center: face center, never downward (owner: "rise up towards more of the center"). */
    fun risenCenterY(cyPx: Float, viewportHPx: Float): Float =
        if (viewportHPx <= 0f) cyPx else minOf(cyPx, viewportHPx / 2f)

    /** Settings row label for a slot ("12:00" … "10:30") — the clock face, stated in words. */
    fun clockLabel(slot: Int): String {
        val i = ((slot % SLOT_COUNT) + SLOT_COUNT) % SLOT_COUNT
        val hour = (i * 45 / 360f * 12f) // 0,1.5,3,…
        val h = (hour.toInt() % 12)
        val m = if (hour % 1f == 0.5f) "30" else "00"
        return "${if (h == 0) 12 else h}:$m"
    }

    /** Persisted form of the map (`keyboard,mute,record_hi,…`). */
    fun encode(slots: List<Lane>): String = slots.joinToString(",") { it.id }

    /**
     * Saved map → exactly [SLOT_COUNT] lanes. Garbage-tolerant by design: an unknown id (a lane
     * renamed in a future build) keeps THAT slot's default instead of blanking the whole dial, and
     * a blank/corrupt value falls back to the owner's default map.
     */
    fun decode(raw: String?): List<Lane> {
        val parts = raw?.split(",")?.map { it.trim() } ?: emptyList()
        return List(SLOT_COUNT) { i ->
            parts.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { Lane.fromId(it) } ?: DEFAULT_SLOTS[i]
        }
    }

    private fun dist(dxPx: Float, dyPx: Float): Float = sqrt(dxPx * dxPx + dyPx * dyPx)
}
