package dev.hermes.wearos.domain

import java.security.SecureRandom

/**
 * PAIRING CODE (SPEC §4 onboarding step 2, shipped as the S5 Pair flow): the watch-minted 6-digit
 * PIN the `hermes watch pair` CLI claims through the plugin's funnel endpoint. Rules the flow needs
 * are pure math/text, so they live here (JVM-tested like every other domain/ state machine):
 *
 *  • minted by the WATCH, crypto-random, 100000..999999 — no leading zero, so the pin reads as
 *    six real digits in the CLI prompt (and a typed `0xxxxx` is an honest typo, not a lost digit);
 *  • one-shot [TTL_MS] window (5 min), re-mintable at will — a spent/expired PIN is just a new mint;
 *  • the tailnet HANDLE (the `<tailnet>` label the funnel domain is built from) is normalized
 *    here so the Pair screen, AppSettings, and PairingClient agree on one canonical form.
 */
object PairingCode {
    /** How long one minted PIN lives: 5 minutes (SPEC §4 "6-digit one-shot code (5-min TTL)"). */
    const val TTL_MS = 5 * 60_000L

    private val random = SecureRandom()

    /** 6 digits, first non-zero. [rng] injectable for tests (any Int maps into the uniform range). */
    fun mint(rng: () -> Int = { random.nextInt(900_000) }): String {
        val v = ((rng() % 900_000) + 900_000) % 900_000
        return (100_000 + v).toString()
    }

    /** Shape check for a PIN coming back from the wire (6 digits, no leading zero). */
    fun isPin(pin: String): Boolean =
        pin.length == 6 && pin[0] != '0' && pin.all { it.isDigit() }

    /** The PIN is dead at mint + TTL; the screen says so instead of polling a 410 forever. */
    fun isExpired(mintedAtMs: Long, nowMs: Long, ttlMs: Long = TTL_MS): Boolean =
        nowMs - mintedAtMs >= ttlMs

    /**
     * Canonical tailnet handle: `My-Home` → `my-home`. Accepts what a human pastes — a bare
     * handle, `handle.ts.net`, the funnel host `pair.handle.ts.net`, or a full URL — and returns
     * "" for anything that is not a single safe DNS-ish label sequence (it feeds a hostname).
     */
    fun normalizeHandle(raw: String): String {
        var h = raw.trim().lowercase()
        if (h.startsWith("https://")) h = h.removePrefix("https://")
        else if (h.startsWith("http://")) h = h.removePrefix("http://")
        if (h.startsWith("pair.")) h = h.removePrefix("pair.")
        h = h.substringBefore('/').trimEnd('.', '-')
        if (h.endsWith(".ts.net")) h = h.removeSuffix(".ts.net")
        return if (h.matches(Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$"))) h else ""
    }
}
