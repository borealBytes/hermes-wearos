package dev.hermes.wearos.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One Hermes gateway the watch can reach (owner multi-gateway epic, SPEC §11). Endpoints are
 * ORDERED candidate bases WITHOUT the `/p/<agent>` suffix — the profile segment stays derived
 * exactly as before (ProfileLogic), so a gateway serves every agent on that host. The first
 * api/watchkit endpoint is the DEFAULT entry (Failover advances through the list in order).
 * Under "tailnet only, always" (SPEC §11.4) every endpoint is reached THROUGH the embedded
 * tunnel node — a user-added LAN literal is just another address the tunnel dials, never a
 * shipped default. `token` is the gateway's API_SERVER_KEY; the watchkit bearer stays a
 * separate global setting (watchkit has its own token by design).
 */
@Serializable
data class Gateway(
    val id: String,
    val label: String,
    val apiEndpoints: List<String>,
    val watchkitEndpoints: List<String>,
    val token: String = "",
    /**
     * PER-AGENT gateway keys, `profile -> API_SERVER_KEY` (SPEC §12.2). This exists because a
     * multiplexed Hermes gateway resolves `API_SERVER_KEY` **per profile from the URL** and fails
     * CLOSED for a named profile with no key: the wear token that works on `/p/wear` is a 401 on
     * `/p/research`, while `/v1/health` (unauthenticated) keeps reporting 200 — so the in-app Test
     * and the failover probe both look fine for a profile the watch can actually use for nothing.
     * Learned automatically from watchkit `GET /agent_keys` (it reads `profiles/<p>/.env` on the
     * gateway box), editable in Settings ▸ Gateways ▸ Agent keys.
     *
     * Adding a field to this record is rollback-safe: the JSON decodes with `ignoreUnknownKeys`,
     * so an older watch reading a newer blob simply ignores it (and defaults here are empty).
     */
    val agentKeys: Map<String, String> = emptyMap(),
) {
    val primaryApi: String get() = apiEndpoints.firstOrNull().orEmpty()
    val primaryWatchkit: String get() = watchkitEndpoints.firstOrNull().orEmpty()

    /** This agent's key on this gateway, falling back to the gateway token (SPEC §12.2 order). */
    fun keyFor(agent: String): String =
        agentKeys[agent]?.trim()?.takeIf { it.isNotEmpty() } ?: token.trim()
}

/**
 * Pure gateway registry: seed defaults, legacy-key migration, CRUD, JSON (de)serialization, and
 * the base-URL fold that keeps `base_url` (the mirror every consumer reads) consistent with the
 * gateway list. No android.* imports — JVM-tested (GatewayModelTest).
 */
object GatewayModel {
    /** Migration/placeholder gateway: tailnet identity ONLY ("tailnet only, always",
     *  SPEC §11.4 — the LAN fallback shipped pre-§11.4 is retired; a user-added LAN literal
     *  is still accepted, it just rides the tunnel like every other address).
     *  PLACEHOLDER — replace via Settings ▸ Gateways, Pair, or watchos.config.json
     *  + scripts/setup.sh; every field is editable in-app. */
    const val SEED_ID = "hermes"
    const val SEED_LABEL = "hermes"
    const val TAILNET_API = "http://hermes.tailnet.ts.net:8642"
    const val TAILNET_WATCHKIT = "http://hermes.tailnet.ts.net:42651"

    // Stock URLs shipped by the pre-publication builds (the author's own box) are kept ONLY as
    // SHA-256 hashes, so neither the original machine name nor its tailnet/LAN addresses appear
    // anywhere in the public source. Migration treats a hash match as "untouched" exactly like
    // the plaintext sets did; runtime behavior is unchanged. The retired LAN placeholders live
    // here too: an untouched old install still migrates as untouched even though no LAN default
    // ships in plaintext any more.
    private val STOCK_API_HASHES = setOf(
        "b26b44efd05c19faedbd5013d468a3594dd0800b432787e154cfc5ad723b6613",  // pre-publication tailscale IP
        "bdd7614b75ac97eec1bb40244ee04826c30f7e0f3333d6c491542c807f6295e2",  // pre-publication tailnet FQDN
        "1c40e2aa2a7527905ed5d5e16f42693e95ddbbc5eee1d6d4a37cdafacd75d430",  // pre-publication LAN IP
        "aed30e92d80bcd06b6eec83b56afab6ca68997f77153b4e323069aa872b2490b",  // retired LAN_API placeholder
    )
    private val STOCK_WATCHKIT_HASHES = setOf(
        "d48bff43b71e37f4520a2a134ce5c47b317429f601999d52646e6d3396a12e56", "0b36e3f4218c9a2145cb26de491c366c07001e322238f3318d6e57021e3dca86", "e69cee7ce67df00905ad523782622d1888dcfeeb022876154c18ddab752ec213",
        "0dff167bc96cb76d28b0831c0cc6977a64a45bef745936dedbb070804ec38375",  // retired LAN_WATCHKIT placeholder
        "024ac05609ff2a3375d39b9263297f61fe9c3bb38fb1a57ed1c0fe7cadff4064",  // retired LAN watchkit :8651
    )

    /** Stock defaults (all shipped builds) that migration treats as "untouched". */
    private val STOCK_API = setOf(TAILNET_API)
    // The published example once used :8651 for watchkit (moved to :42651, SPEC §11.4) — builds
    // installed back then still carry those URLs and must migrate as untouched, not custom.
    private val STOCK_WATCHKIT = setOf(TAILNET_WATCHKIT, "http://hermes.tailnet.ts.net:8651")

    private fun sha256hex(url: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun isStockApi(ep: String) = ep in STOCK_API || sha256hex(ep) in STOCK_API_HASHES

    private fun isStockWatchkit(wk: String) = wk in STOCK_WATCHKIT || sha256hex(wk) in STOCK_WATCHKIT_HASHES

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(Gateway.serializer())

    /** Tailnet-FQDN-only defaults (SPEC §11.4): the tunnel is THE transport, nothing else ships. */
    val DEFAULT_API_ENDPOINTS = listOf(TAILNET_API)
    val DEFAULT_WATCHKIT_ENDPOINTS = listOf(TAILNET_WATCHKIT)

    /** Placeholder gateway for untouched installs: the tailnet URL is the ONLY DEFAULT entry. */
    fun seed(token: String = ""): Gateway =
        Gateway(SEED_ID, SEED_LABEL, DEFAULT_API_ENDPOINTS, DEFAULT_WATCHKIT_ENDPOINTS, token.trim())

    /** Trim + drop trailing slash + strip a `/p/<agent>` suffix (endpoints are host:port-level). */
    fun normalizeEndpoint(url: String): String {
        val u = url.trim().trimEnd('/')
        if (u.isEmpty()) return ""
        val scheme = u.indexOf("://")
        if (scheme < 0) return u
        val from = u.indexOf('/', scheme + 3)
        if (from < 0) return u
        val idx = u.indexOf("/p/", from)
        return if (idx < 0) u else u.substring(0, idx).trimEnd('/')
    }

    /** The profile-scoped base every network consumer uses: endpoint + `/p/<agent>`. */
    fun apiBase(endpoint: String, profile: String): String {
        val e = normalizeEndpoint(endpoint)
        if (e.isEmpty() || !ProfileLogic.isValidProfile(profile)) return e
        return "$e/p/$profile"
    }

    fun encode(list: List<Gateway>): String = json.encodeToString(listSerializer, list)

    /** Garbage/blank (corrupt DataStore blob) decodes to empty — callers then re-migrate. */
    fun decode(raw: String): List<Gateway> = try {
        if (raw.isBlank()) emptyList() else json.decodeFromString(listSerializer, raw)
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * ONE-TIME UPGRADE (SPEC §11): fold the legacy single-endpoint keys (base_url / token /
     * watchkit_url) into the gateway registry. Legacy keys are NEVER removed — they stay the
     * live mirror (syncMirror writes them), so the watch keeps working either way.
     *
     * Rules (owner-locked, tailnet-only doctrine SPEC §11.4):
     *  - fresh install (no legacy endpoint at all) → EMPTY registry: the caller opens the
     *    Pair screen instead of silently seeding a placeholder the tunnel cannot reach;
     *  - untouched/stock placeholder values → seed(): tailnet-FQDN-only defaults;
     *  - a CUSTOM host keeps its endpoint FIRST (what worked today keeps working first), the
     *    tailnet default follows so failover still applies through the tunnel;
     *  - token migrates verbatim onto the gateway.
     */
    fun migrate(
        legacyBaseUrl: String,
        legacyToken: String,
        legacyWatchkitUrl: String,
    ): Pair<List<Gateway>, String> {
        val token = legacyToken.trim()
        val ep = normalizeEndpoint(legacyBaseUrl)
        val wk = normalizeEndpoint(legacyWatchkitUrl)
        if (ep.isEmpty()) return emptyList<Gateway>() to "" // fresh install → Pair screen, no placeholder
        if (isStockApi(ep)) {
            val g = seed(token)
            val withWk = if (wk.isNotEmpty() && !isStockWatchkit(wk))
                g.copy(watchkitEndpoints = (listOf(wk) + g.watchkitEndpoints).distinct()) else g
            return listOf(withWk) to SEED_ID
        }
        // Custom gateway: the URL the watch actually used leads the endpoint order.
        val apis = (listOf(ep) + DEFAULT_API_ENDPOINTS).distinct()
        val wkFirst = if (wk.isNotEmpty() && !isStockWatchkit(wk)) wk else derivedWatchkit(ep)
        val wks = ((if (wkFirst.isEmpty()) emptyList() else listOf(wkFirst)) + DEFAULT_WATCHKIT_ENDPOINTS).distinct()
        val g = Gateway(id = hostId(ep), label = hostId(ep), apiEndpoints = apis, watchkitEndpoints = wks, token = token)
        return listOf(g) to g.id
    }

    /**
     * THE KEY RESOLVE ORDER (SPEC §12.2): the agent's own key → the selected gateway's token →
     * the legacy mirror token. The last step keeps a single-gateway watch (or a pre-registry
     * install) behaving exactly as it does today, so this can never make a working agent worse.
     */
    fun tokenFor(gateway: Gateway?, agent: String, mirrorToken: String): String =
        gateway?.keyFor(agent)?.takeIf { it.isNotEmpty() } ?: mirrorToken.trim()

    /** Same host, watchkit port convention (:42651) — owner's layout on every gateway box. */
    fun derivedWatchkit(apiEndpoint: String): String {
        val u = normalizeEndpoint(apiEndpoint)
        val scheme = u.indexOf("://")
        if (scheme < 0) return ""
        val authority = (u.substring(scheme + 3).substringBefore('/')).substringBefore('@').substringAfterLast('@')
        val host = authority.substringBefore(':')
        if (host.isEmpty()) return ""
        return u.substring(0, scheme + 3) + (if (authority.contains(':')) authority.substringBeforeLast(':') else host) + ":42651"
    }

    /** Stable id from a URL host: `http://vps.example.com:8642` → `vps-example-com`. */
    fun hostId(url: String): String {
        val u = normalizeEndpoint(url)
        val scheme = u.indexOf("://")
        val authority = if (scheme < 0) u else u.substring(scheme + 3).substringBefore('/')
        val host = authority.substringAfterLast('@').substringBeforeLast(':').ifEmpty { authority }
        val id = host.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-')
        return id.ifEmpty { "gateway" }
    }

    /** CRUD: replace by id, else append. Blank fields fall back to sane values. */
    fun upsert(list: List<Gateway>, gw: Gateway): List<Gateway> {
        val clean = gw.copy(
            label = gw.label.trim().ifEmpty { gw.id.ifEmpty { "gateway" } },
            apiEndpoints = gw.apiEndpoints.map { normalizeEndpoint(it) }.filter { it.isNotEmpty() }.distinct(),
            watchkitEndpoints = gw.watchkitEndpoints.map { normalizeEndpoint(it) }.filter { it.isNotEmpty() }.distinct(),
            token = gw.token.trim(),
        )
        require(clean.apiEndpoints.isNotEmpty()) { "gateway needs at least one API endpoint" }
        val id = clean.id.ifEmpty { hostId(clean.primaryApi) }
        val withId = clean.copy(id = id)
        val idx = list.indexOfFirst { it.id == id }
        return if (idx >= 0) list.toMutableList().also { it[idx] = withId } else list + withId
    }

    fun remove(list: List<Gateway>, id: String): List<Gateway> = list.filter { it.id != id }

    /** Selected id after a delete: selected gone → the next surviving gateway (or empty). */
    fun selectedAfterRemove(selected: String, list: List<Gateway>, removedId: String): String =
        if (selected != removedId) selected else (list.firstOrNull { it.id != removedId }?.id ?: "")

    /**
     * Fold a direct `base_url` edit into the selected gateway: an endpoint already on the list
     * KEEPS ITS POSITION (failover winner/agent switches re-set the base constantly — never
     * reorder the owner's order through them); an unknown endpoint PREPENDS (user intent).
     */
    fun foldBaseUrl(list: List<Gateway>, selectedId: String, newBaseUrl: String): List<Gateway> {
        val ep = normalizeEndpoint(newBaseUrl)
        if (ep.isEmpty()) return list
        val idx = list.indexOfFirst { it.id == selectedId }
        if (idx < 0) return list
        val g = list[idx]
        if (g.apiEndpoints.contains(ep)) return list
        return list.toMutableList().also { it[idx] = g.copy(apiEndpoints = listOf(ep) + g.apiEndpoints) }
    }

    /**
     * Fold a watchkit-URL edit into the selected gateway — the symmetric twin of [foldBaseUrl]
     * (owner bug 2026-10-29: `set_watchkit_base`/Settings wrote only the mirror, so a gateway's
     * wk endpoint list could never gain a fallback while its API list could). Same rules: known
     * endpoint keeps its position, unknown one prepends; every OTHER gateway is untouched.
     */
    fun foldWatchkit(list: List<Gateway>, selectedId: String, newWatchkitUrl: String): List<Gateway> {
        val wk = normalizeEndpoint(newWatchkitUrl)
        if (wk.isEmpty()) return list
        val idx = list.indexOfFirst { it.id == selectedId }
        if (idx < 0) return list
        val g = list[idx]
        if (g.watchkitEndpoints.contains(wk)) return list
        return list.toMutableList().also { it[idx] = g.copy(watchkitEndpoints = listOf(wk) + g.watchkitEndpoints) }
    }

    /** Fold a token edit onto the selected gateway (the mirror key carries it to consumers). */
    fun foldToken(list: List<Gateway>, selectedId: String, token: String): List<Gateway> {
        val idx = list.indexOfFirst { it.id == selectedId }
        if (idx < 0) return list
        return list.toMutableList().also { it[idx] = list[idx].copy(token = token.trim()) }
    }
}
