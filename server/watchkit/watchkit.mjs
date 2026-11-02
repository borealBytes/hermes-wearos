#!/usr/bin/env node
/**
 * watchkit — tiny helper daemon for the Hermes Wear OS watch. Two heavy-lift services
 * the watch can't do on-device, behind one bearer token, tailnet-only:
 *
 *   GET  /health          → {status:"ok", version, uptime_s}                (public probe)
 *   GET  /agents          → {agents:["default", …profiles/<name>/ with config.yaml]}  (bearer)
 *   GET  /sessions        → gateway-shaped session list for a profile (bearer)
 *        ?profile=<name>&limit=<n≤30> → {object:"list", data:[{id,title,last_active,message_count,profile}]}
 *        Reads the profile's SQLite state.db DIRECTLY (same store the dashboard's GET /api/sessions
 *        reads) — dashboard auth is scrypt basic-auth with no recoverable plaintext, and watchkit
 *        is on the same box, so reading beats proxying. Hidden/archived/cron/tool/subagent rows and
 *        compression-continuation children are filtered out (desktop-sidebar semantics, newest-first).
 *   GET  /session_messages → transcript backfill for an adopted session (bearer)
 *        ?profile=<name>&session_id=<id>&limit=<n≤120> → {messages:[{role,content,ts}],session_id,profile,count}
 *        Same state.db, `messages` table: user/assistant + active + non-empty content, compression
 *        summary rows surfacing as role:"summary", noise display_kinds dropped, NEWEST [limit] rows
 *        returned oldest-first, per-message content capped at 2KB. Empty list for unknown ids (200).
 *   POST /health/snapshot → {ok:true}   body = watch body-tools snapshot JSON (W1-W8, bearer)
 *   GET  /health/latest   → latest stored snapshot (bearer, 404 before first push)
 *   GET  /health/tool/<name> → per-tool MCP-style answer derived from the snapshot
 *        watch_heart_rate · watch_steps · watch_sleep · watch_spo2
 *        {tool, ok:true, data:{…}} | {tool, ok:false, reason} | {tool, ok:false, stale:true, age_s}
 *   GET  /img             → scaled image/webp ?path=…&width=460 (allowlisted scaler, Pillow → WebP q82)
 *        width OPTIONAL (owner batch 2): no width (or width=0) ⇒ ORIGINAL bytes served, no re-encode,
 *        for png/jpeg/webp (watch downsamples at decode); gif/bmp still fall back to the 460 scaler.
 *        15MB cap + img_roots allowlist always apply. X-Cache key = realpath|mtime|width ("full").
 *   POST /img             → same, JSON body {path[,width]}
 *   GET  /media           → scaled A/V stream (bearer, HTTP Range). ?path=…[&q=0..100&mono=1&ab=64]
 *        → mp4 (H.264/AAC faststart) for video extensions, m4a (AAC) for audio; ffmpeg pre-shrinks
 *        to watch-size presets and caches per realpath|mtime|params (.cache/media). &poster=1 → one
 *        JPEG frame for the inline card; &probe=1 → {duration_s,width,height,kind}. img_roots apply.
 *   POST /render/mermaid  → image/png  {code, width, format?}  (mmdc, .cache/ renders)
 *   POST /stt/whisper     → {text}     multipart "audio" wav/webm ≤10MB     (faster-whisper CPU)
 *   WS   /stt/stream      ← BINARY 16 kHz mono PCM16 mic frames from the watch, TEXT {"end":true}
 *        (bearer)          → TEXT {"partial":"…"}* / {"final":"…"} / {"error":"…"}   (batch-4 #9)
 *        Engine default from config.json `stt_stream_engine`: "whisper" (buffered — final only)
 *        | "parakeet" (transcribe.cpp GGUF — partials WHILE talking). The watch may override per
 *        session via ?engine=<id>; GET /health reports stt:{engine,ready,missing,engines[]}.
 *
 * Zero framework: node:http + node:crypto only. Everything folder-local under
 * server/watchkit/ (.cache for renders+npx+puppeteer, .models for whisper weights).
 *
 * Auth: `Authorization: Bearer <token>` from config.json (see config.example.json).
 * NOTE v1: the whisper python process is COLD-START on every request (~2-4s model load).
 * Keeping it warm (persistent worker/stdin loop) is a deliberate TODO, not a bug.
 */
import http from 'node:http';
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import { attachSttStream, sttEngineStatus } from './stt_stream_ws.mjs';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';

const WK_DIR = path.dirname(fileURLToPath(import.meta.url));
// Version comes from repo-root VERSION (single source of truth — scripts/version.sh).
const VERSION = (() => {
  try { return fs.readFileSync(path.join(WK_DIR, '..', '..', 'VERSION'), 'utf8').trim(); }
  catch { return '0.0.0'; }
})();

// ── config ─────────────────────────────────────────────────────────────────
const CONFIG_PATH = process.env.WATCHKIT_CONFIG || path.join(WK_DIR, 'config.json');
let cfg = {};
try {
  cfg = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
} catch (e) {
  // MCP stdio mode is often launched with WATCHKIT_TOKEN/WATCHKIT_URL env only: config.json
  // may be absent there. HTTP mode keeps the old hard-fail behavior.
  if (!process.env.WATCHKIT_TOKEN) {
    console.error(`watchkit: cannot read ${CONFIG_PATH} (${e.message}) — cp config.example.json config.json + set token`);
    process.exit(2);
  }
  console.error(`watchkit: config.json unreadable (${e.message}) — continuing on WATCHKIT_TOKEN env`);
}
const TOKEN = process.env.WATCHKIT_TOKEN || cfg.token || '';
if (!TOKEN) { console.error('watchkit: empty token in config'); process.exit(2); }
const HOST = process.env.WATCHKIT_HOST || cfg.host || '127.0.0.1'; // bind: set your LAN/tailnet IP in config.json
const PORT = Number(process.env.WATCHKIT_PORT || cfg.port || 42651);
// Stdio MCP mode (`node watchkit.mjs mcp`): stdout is reserved for JSON-RPC; logs go to stderr.
const MCP_MODE = process.argv.slice(2).includes('mcp');
const IMG_TIMEOUT_MS = 30_000;        // Pillow scale hard cap (used by handleImg)
const RENDER_TIMEOUT_MS = 60_000;   // mmdc (chromium) hard cap
const STT_TIMEOUT_MS = 55_000;      // python whisper hard cap
const AUDIO_MAX_BYTES = 10 * 1024 * 1024;
const JSON_MAX_BYTES = 1024 * 1024;

// ── folders (all folder-local, gitignored) ─────────────────────────────────
const CACHE_DIR = path.join(WK_DIR, '.cache');
const TMP_DIR = path.join(CACHE_DIR, 'tmp');
const NPM_CACHE = path.join(CACHE_DIR, 'npm');          // npx package cache
const PUPPETEER_CACHE = path.join(CACHE_DIR, 'puppeteer'); // chromium download
const MODELS_DIR = path.join(WK_DIR, '.models');
const VENV_PY = path.join(WK_DIR, '.venv', 'bin', 'python');
const TRANSCRIBER = path.join(WK_DIR, 'whisper_transcribe.py');
const PUPPETEER_CFG = path.join(CACHE_DIR, 'puppeteer.json');
const MEDIA_DIR_EARLY = path.join(CACHE_DIR, 'media');
for (const d of [CACHE_DIR, TMP_DIR, NPM_CACHE, PUPPETEER_CACHE, MODELS_DIR, MEDIA_DIR_EARLY]) fs.mkdirSync(d, { recursive: true });
fs.writeFileSync(PUPPETEER_CFG, JSON.stringify({ args: ['--no-sandbox', '--disable-dev-shm-usage'] }));

const START_MS = Date.now();
const CHILD_ENV = {
  ...process.env,
  PUPPETEER_CACHE_DIR: PUPPETEER_CACHE,
  npm_config_cache: NPM_CACHE,
  // keep any HF hub side-cache off ~/.cache too:
  HF_HOME: path.join(MODELS_DIR, 'hf'),
};

// ── body-tools snapshot (SPEC W8: watch → cache → MCP tools) ───────────────
// The watch POSTs its latest body snapshot; we keep only the freshest one in .state/snapshot.json
// (atomic write, same fs) and derive per-tool answers for MCP tools/call. Stale >2h ⇒ ok:false
// with stale:true — "heart rate 41" from a dead watch is worse than no answer.
// ── agents (profile discovery for the watch's agent switcher) ──────────────
// Same answer the desktop sidebar gives: the multiplexed `default` home + every profiles/<name>/
// that has a config.yaml (a half-created dir is not an agent). Read-only directory scan.
const PROFILES_DIR = process.env.WATCHKIT_PROFILES || cfg.profiles_dir || path.join(os.homedir(), '.hermes', 'profiles');

function listAgents() {
  const agents = ['default'];
  let names = [];
  try { names = fs.readdirSync(PROFILES_DIR); } catch { /* profiles dir missing: default only */ }
  for (const name of names.sort()) {
    if (name.startsWith('.')) continue; // dot-dirs are staging/temp scaffolding, not agents
    try {
      const dir = path.join(PROFILES_DIR, name);
      if (fs.statSync(dir).isDirectory() && fs.existsSync(path.join(dir, 'config.yaml'))) agents.push(name);
    } catch { /* vanished mid-scan / unreadable — skip */ }
  }
  return agents;
}

// ── command catalog (the watch's ⌘ / 6:00 dial overlay) ────────────────────
// The watch's slash overlay must never pretend the Runs API dispatches slash commands (it does
// not: `POST /v1/runs` turns `input` into a MODEL message), so every row declares `honored` =
// what the WATCH does with it. Rows come from one place and one place only on this box:
//   <watchkit>/command_catalog.json  — optional, operator-authored, hot-reloaded per request
//     {"commands":[{"name":"steer","args":"your guidance","desc":"inject into the run in flight",
//                  "honored":"steer","group":"commands","requires":"run_steer","source":"gateway"}],
//      "capabilities":{"run_steer":true}}
// With no file we answer with the DEFAULT_ROWS below, so a fresh watchkit still lights the overlay
// up with real rows instead of nothing. Capability FLAGS are NOT guessed here: the watch reads
// `GET /p/<agent>/v1/capabilities` itself (it holds that profile's key) and dims rows from the
// truth — watchkit never claims a feature it cannot see. Skill rows ride the same response:
// watchkit reads `state.db` for profiles the watch has no key for, so `GET /catalog?profile=x`
// returns that agent's SKILLs too (from its skills/ dir), which is why the watch needs no
// skills endpoint of its own.
const CATALOG_PATH = process.env.WATCHKIT_CATALOG || path.join(WK_DIR, 'command_catalog.json');
const SKILL_ROW_CAP = Number(process.env.WATCHKIT_SKILL_CAP || 120); // overlay caps the view too
const DEFAULT_ROWS = [
  { name: 'steer', args: 'your guidance', desc: 'inject guidance into the run in flight', honored: 'steer', group: 'commands', requires: 'run_steer' },
  { name: 'stop', desc: 'stop the run in flight', honored: 'stop', group: 'commands', requires: 'run_stop' },
  { name: 'btw', args: 'aside', desc: 'side conversation in a fork — keeps this thread clean', honored: 'side', group: 'commands', requires: 'session_fork' },
  { name: 'fork', desc: 'branch this session and continue there', honored: 'fork', group: 'commands', requires: 'session_fork' },
  { name: 'model', desc: 'change the model for this session', honored: 'model', group: 'commands', requires: 'model_options' },
  { name: 'compact', desc: 'compress context (asked as a prompt — the gateway decides)', honored: 'prompt', group: 'commands' },
  { name: 'status', desc: 'gateway + watchkit health, right now', honored: 'status', group: 'client' },
  { name: 'sessions', desc: 'gateway/computer sessions', honored: 'client', group: 'client' },
  { name: 'chats', desc: 'local chats for this agent', honored: 'client', group: 'client' },
  { name: 'agents', desc: 'switch agent', honored: 'client', group: 'client' },
  { name: 'new', desc: 'start a fresh chat', honored: 'client', group: 'client' },
  { name: 'settings', desc: 'settings', honored: 'client', group: 'client' },
];

function skillRows(profile) {
  // <profile>/skills/<name>/SKILL.md → a slash row. Same shape the desktop sidebar lists; a
  // missing/odd dir is simply no rows (a gateway without skills must not look broken).
  const dir = path.join(PROFILES_DIR, profile === 'default' ? '' : profile, 'skills');
  const out = [];
  let names = [];
  try { names = fs.readdirSync(dir); } catch { return out; }
  for (const name of names.sort()) {
    if (name.startsWith('.')) continue;
    try {
      const md = path.join(dir, name, 'SKILL.md');
      if (!fs.existsSync(md)) continue;
      const head = fs.readFileSync(md, 'utf8').slice(0, 4000);
      const fm = /^---\n([\s\S]*?)\n---/.exec(head);
      const desc = fm && /(?:^|\n)description:\s*(.+)/.exec(fm[1]);
      out.push({
        name: name.replace(/\s+/g, '-').toLowerCase(),
        args: '',
        desc: (desc ? desc[1] : '').replace(/^["']|["']$/g, '').slice(0, 140),
        honored: 'skill', group: 'skills', source: profile,
      });
    } catch { /* skip unreadable skill dir */ }
  }
  return out;
}

async function handleCatalog(req, res, ip, params) {
  const profile = String(params.profile || 'default').replace(/[^a-z0-9_-]/gi, '').slice(0, 40) || 'default';
  let commands = DEFAULT_ROWS, caps = {};
  try {
    const raw = JSON.parse(fs.readFileSync(CATALOG_PATH, 'utf8'));
    if (Array.isArray(raw.commands) && raw.commands.length) commands = raw.commands;
    if (raw.capabilities && typeof raw.capabilities === 'object') caps = raw.capabilities;
  } catch { /* no file / bad JSON: defaults answer, which is the point of shipping them */ }
  // A profile with 200 skills must not deliver a 200-row menu: the watch shows the SKILLS section
  // collapsed-until-filtered, and we cap the payload too (alphabetical, stable) so a huge skill
  // dir can't turn a catalog fetch into a megabyte. `truncated` tells the watch to say so.
  const skills = skillRows(profile);
  const capped = skills.slice(0, SKILL_ROW_CAP);
  const rows = commands.concat(capped);
  const body = JSON.stringify({
    commands: rows, capabilities: caps, profile,
    skill_count: skills.length, truncated: skills.length > capped.length,
  });
  return done(res, send(res, 200, body), Date.now(), ip, 'catalog', 200, undefined, rows.length);
}

// ── agent keys (the multi-agent 401 fix, SPEC §12.2) ──────────────────────
// A multiplexed Hermes gateway resolves API_SERVER_KEY PER PROFILE from the URL and fails CLOSED
// for a named profile that has none: the wear token is a 401 on /p/research while /v1/health
// (unauthenticated) keeps answering 200 — so the watch's Test button and endpoint failover both
// look healthy for an agent that can do nothing. watchkit is the only component that can see the
// truth, because it runs on the gateway box and reads the profile dotfiles:
//   profiles/<p>/.env   → API_SERVER_KEY for <p>       (absent ⇒ that agent needs a key typed)
//   ~/.env (or the home profile) → API_SERVER_KEY for `default`
// Response (bearer-protected, tailnet/LAN-only port like every other route):
//   {"agents":[{"profile":"wear","has_key":true,"key":"…"},{"profile":"research","has_key":false}],
//    "source":{"default":"home-env"}}
// Returning the KEYS is deliberate: this port is not public, the watch needs them to sign requests
// per profile, and "switch agent, get a 401, type 64 hex chars on a watch" is not a workflow.
// `has_key:false` is the honest answer the UI shows instead of a mystery error.
function readEnvKey(file) {
  try {
    const txt = fs.readFileSync(file, 'utf8');
    const m = /^API_SERVER_KEY=["']?([^"'\n]+)["']?$/m.exec(txt);
    return m ? m[1].trim() : '';
  } catch { return ''; }
}

function agentKeys() {
  const out = [];
  // The `default` profile's env is the HERMES_HOME one (~/.hermes/.env) — same rule openStore
  // uses for its state.db. `~/.env` was a wrong guess: it made the biggest store report no key.
  const homeKey = readEnvKey(path.join(HERMES_HOME, '.env'));
  for (const profile of listAgents()) {
    const key = profile === 'default'
      ? (readEnvKey(path.join(PROFILES_DIR, 'default', '.env')) || homeKey)
      : readEnvKey(path.join(PROFILES_DIR, profile, '.env'));
    out.push({ profile, has_key: !!key, key });
  }
  return out;
}

async function handleAgentKeys(req, res, ip) {
  seedProfileKeys(true); // a LEARN is the moment new agents should join the map (owner below)
  const agents = agentKeys();
  const withKey = agents.filter((a) => a.has_key).length;
  const body = JSON.stringify({ agents, source: 'profiles-env' });
  return done(res, send(res, 200, body), Date.now(), ip, 'agent_keys', 200, undefined, withKey);
}

/**
 * AUTO-KEY NEW AGENTS (owner 2026-11-02: "two new agents had no keys and Learn said none —
 * watchkit should look for new agents and get that key in there"). Agents created AFTER first
 * setup carry no `API_SERVER_KEY` in their profile .env, and the gateway fails CLOSED for a
 * keyless named profile (SPEC §12.2) — while the one-login convention says the default profile's
 * key (read from ~/.hermes/.env) is THE key on this box. So: append it to every keyless profile,
 * exactly the `scripts/setup.sh --agent-keys` recipe, but automatic. `force` = a Learn request
 * always sweeps; otherwise the away-sweep poll (every 20 s) throttles this to once a minute —
 * new agents are keyed within a minute of appearing, with no human step. Never logs key values;
 * append-only, and a missing .env is created (0600 like its siblings).
 */
let lastKeySeedMs = 0;
function seedProfileKeys(force = false) {
  const now = Date.now();
  if (!force && now - lastKeySeedMs < 60_000) return [];
  lastKeySeedMs = now;
  const homeKey = readEnvKey(path.join(HERMES_HOME, '.env'));
  if (!homeKey) return [];
  const seeded = [];
  for (const profile of listAgents()) {
    if (profile === 'default') continue; // default resolves ~/.hermes/.env directly in agentKeys()
    const envPath = path.join(PROFILES_DIR, profile, '.env');
    try {
      if (readEnvKey(envPath)) continue;
      let body = '';
      try { body = fs.readFileSync(envPath, 'utf8'); } catch { /* no .env yet — one gets made */ }
      const glue = body && !body.endsWith('\n') ? '\n' : ''; // never glue onto a newline-less tail
      fs.appendFileSync(envPath, `${glue}API_SERVER_KEY=${homeKey}\n`, { mode: 0o600 });
      seeded.push(profile);
    } catch { /* vanished profile / read-only fs — that agent just stays keyless this round */ }
  }
  if (seeded.length) logLine({ ev: 'agent_keys_seeded', profiles: seeded });
  return seeded;
}

// ── /search (session search over the profile's FTS index, SPEC §12.4) ───────
// The owner's Sessions screen ask: "new session plus a tap-to-search box" — searchable across
// every chat this agent ever had, not just the 20 rows /sessions lists. The store already carries
// a maintained FTS5 index (`messages_fts`, triggers keep it in step with `messages`), so search is
// a MATCH + join at ~ms on a 900 MB store — no scan, no new index, no gateway round trip.
//   GET /search?profile=<p>&q=<words>&limit=8
//   {"object":"list","profile":"wear","query":"orb dial","data":[
//      {"id","title","last_active","message_count","profile","hits":3,"snippet":"…the orb dial
//       wedges…","snippet_role":"user","title_match":true}]}
// Snippet mechanics: FTS5 `snippet()` marks the hit with [brackets] — the watch shows that string
// verbatim, which is how a wrist sees WHY a result matched. Words AND together, last word gets a
// prefix star (`dial` → `dial*`) so typing mid-word already works; every token is quote-wrapped so
// FTS operators inside the query can't throw syntax errors at us. Sessions the sidebar hides
// (cron/tool/subagent noise, archived) are hidden here too — same visibility rules as /sessions,
// one truth. If the profile has no store or FTS somehow isn't there, the answer is an empty list
// (200), never a 500 the watch has to explain.
const SEARCH_LIMIT_MAX = 12;

function ftsQuery(q) {
  // Words, FTS-safe: quote-wrap each token (internal quotes doubled), AND them, prefix the last.
  const words = String(q || '').split(/\s+/).map((w) => w.replace(/"/g, '')).filter(Boolean).slice(0, 6);
  if (!words.length) return '';
  return words
    .map((w, i) => `"${w}"${i === words.length - 1 ? '*' : ''}`)
    .join(' AND ');
}

async function handleSearch(req, res, ip, params) {
  const t0 = Date.now();
  const profile = String(params.profile ?? 'default').trim() || 'default';
  const q = String(params.q ?? '').trim().slice(0, 96);
  const limit = Math.min(SEARCH_LIMIT_MAX, Math.max(1, Number(params.limit) || 8));
  if (!SESSIONS_PROFILE_RE.test(profile))
    return done(res, sendErr(res, 400, 'bad profile name'), t0, ip, 'search', 400);
  const empty = JSON.stringify({ object: 'list', profile, query: q, data: [] });
  const dbPath = sessionDbPath(profile);
  if (!q || !dbPath || !fs.existsSync(dbPath))
    return done(res, send(res, 200, empty), t0, ip, 'search', q ? 200 : 400, undefined, 0);
  const match = ftsQuery(q);
  let db;
  try {
    const { DatabaseSync } = await import('node:sqlite');
    db = new DatabaseSync(dbPath, { readOnly: true });
    // Best-matching MESSAGES first (FTS rank), rolled up to sessions in JS: the wrist wants
    // "which chat, and here is the line that matched" — one row per session, the single best
    // snippet on it. LIKE over titles adds sessions whose NAME matches even when no message does.
    const rows = db.prepare(`
      SELECT s.id AS id, s.title AS title, s.message_count AS message_count,
             COALESCE(s.last_activity_at, s.started_at) AS last_active,
             m.role AS role,
             snippet(messages_fts, 0, '[', ']', ' · ', 20) AS snippet,
             rank AS rank
      FROM messages_fts f
      JOIN messages m ON m.id = f.rowid
      JOIN sessions s ON s.id = m.session_id
      WHERE messages_fts MATCH ?
        AND s.hidden = 0 AND s.archived = 0
        AND COALESCE(s.source, '') NOT IN ('cron', 'tool', 'subagent')
        AND m.role IN ('user', 'assistant')
      ORDER BY rank ASC
      LIMIT 120
    `).all(match);
    const likeLike = `%${q.replace(/[%_]/g, '').slice(0, 48)}%`;
    const titled = db.prepare(`
      SELECT s.id AS id, 1 AS title_hit FROM sessions s
      WHERE s.title LIKE ? AND s.hidden = 0 AND s.archived = 0
        AND COALESCE(s.source, '') NOT IN ('cron', 'tool', 'subagent')
      LIMIT 40
    `).all(likeLike);
    const titleHits = new Set(titled.map((r) => String(r.id)));
    const bySession = new Map();
    for (const r of rows) {
      const id = String(r.id);
      const hit = bySession.get(id);
      if (hit) { hit.hits += 1; continue; } // first row per session = best rank = the snippet kept
      bySession.set(id, {
        id,
        title: String(r.title ?? ''),
        last_active: saneEpochSec(r.last_active),
        message_count: Number(r.message_count) || 0,
        profile,
        hits: 1,
        snippet: String(r.snippet ?? '').slice(0, 240),
        snippet_role: String(r.role ?? ''),
        title_match: titleHits.has(id),
      });
    }
    // Title-named sessions with zero message hits still belong in the answer.
    for (const id of titleHits) {
      if (bySession.has(id)) continue;
      const r = db.prepare(`
        SELECT s.title AS title, s.message_count AS message_count,
               COALESCE(s.last_activity_at, s.started_at) AS last_active
        FROM sessions s WHERE s.id = ? LIMIT 1
      `).get(id);
      if (!r) continue;
      bySession.set(id, {
        id, title: String(r.title ?? ''), last_active: saneEpochSec(r.last_active),
        message_count: Number(r.message_count) || 0, profile, hits: 0, snippet: '',
        snippet_role: '', title_match: true,
      });
    }
    // Rank: title match first (a chat NAMED what you typed beats a passing mention), then hit
    // count, then recency. FTS rank order is already baked into which snippet we kept + insertion
    // order for equal keys via the stable sort.
    const data = [...bySession.values()]
      .sort((a, b) =>
        (b.title_match - a.title_match) ||
        (b.hits - a.hits) ||
        (b.last_active - a.last_active))
      .slice(0, limit);
    const body = JSON.stringify({ object: 'list', profile, query: q, data });
    return done(res, send(res, 200, body), t0, ip, 'search', 200, undefined, data.length);
  } catch (e) {
    // No FTS table / busy store: empty beats error for a search box.
    return done(res, send(res, 200, empty), t0, ip, 'search', 200, undefined, 0);
  } finally {
    try { db?.close(); } catch { /* already gone */ }
  }
}

// ── session management (the watch's long-press menu: fork / delete) ─────────
// The Sessions screen lets the owner hold a row and FORK it (branch into the CURRENT agent so the
// watch can keep talking in the branch) or DELETE it (double-confirmed on the wrist). Why these
// live here instead of riding the gateway API: the gateway DELETE only works for a profile whose
// key the watch holds — and most profiles have none (SPEC §12.2) — while the desktop "computer"
// chats live in the `default` store, which the watch can never sign for. watchkit runs beside
// `state.db` and can write what it can read, so ONE uniform path works for every agent.
//
//   POST /session_fork   {profile, session_id, to_profile?, new_id, title?}
//     → {object:"hermes.session.forked", id, profile, copied_messages, title}
//   POST /session_delete {profile, session_id}
//     → {object:"hermes.session.deleted", id, profile, deleted:true, messages:n}
//
// Fork copies session + messages column-wise (the INTERSECTION of source/target schemas, so a
// version-skewed pair degrades instead of crashing), re-dates nothing, sets parent_session_id to
// the origin (lineage the desktop already understands), suffixes the title " (fork)", and lands
// the child hidden=0 archived=0 so it appears immediately. INSERTs flow through the store's own
// FTS triggers, so the copy is searchable without anyone rebuilding an index. Cross-profile forks
// ATTACH nothing: the source is read through its own handle and rows are inserted through the
// target's — same fs, one busy_timeout each, no long-lived locks.
//
// Delete is a REAL delete (messages then session) — the watch double-confirms before calling, and
// the FTS delete triggers keep the index honest. Sessions with a run in flight are the owner's own
// risk (they just said "delete"); the store survives, a streaming watch run settles to an error.
function dbCols(db, table) {
  return db.prepare(`PRAGMA table_info(${table})`).all().map((c) => c.name);
}

async function openStore(profile, readOnly) {
  const p = sessionDbPath(profile);
  if (!p || !fs.existsSync(p)) return null;
  const { DatabaseSync } = await import('node:sqlite');
  const db = new DatabaseSync(p, readOnly ? { readOnly: true } : {});
  db.exec('PRAGMA busy_timeout = 5000');
  return db;
}

async function handleSessionFork(req, res, ip, body) {
  const t0 = Date.now();
  const profile = String(body.profile || 'default').trim();
  const toProfile = String(body.to_profile || profile).trim();
  const sessionId = String(body.session_id || '').trim();
  const newId = String(body.new_id || '').trim();
  if (!SESSIONS_PROFILE_RE.test(profile) || !SESSIONS_PROFILE_RE.test(toProfile))
    return done(res, sendErr(res, 400, 'bad profile'), t0, ip, 'session_fork', 400);
  if (!SESSION_ID_RE.test(sessionId) || !SESSION_ID_RE.test(newId))
    return done(res, sendErr(res, 400, 'bad session id'), t0, ip, 'session_fork', 400);
  let src = null, dst = null;
  try {
    src = await openStore(profile, true);
    if (!src) return done(res, sendErr(res, 404, 'source store missing'), t0, ip, 'session_fork', 404);
    const orig = src.prepare('SELECT * FROM sessions WHERE id = ?').get(sessionId);
    if (!orig) return done(res, sendErr(res, 404, 'session not found'), t0, ip, 'session_fork', 404);
    dst = await openStore(toProfile, false);
    if (!dst) { // to-profile may be a never-booted agent — copying INTO a missing store is impossible here
      return done(res, sendErr(res, 503, `target store missing for ${toProfile}`), t0, ip, 'session_fork', 503);
    }
    const clash = dst.prepare('SELECT 1 FROM sessions WHERE id = ?').get(newId);
    if (clash) return done(res, sendErr(res, 409, 'that session id already exists'), t0, ip, 'session_fork', 409);
    const sCols = dbCols(dst, 'sessions').filter((c) => c in orig);
    const title = (String(body.title || '').trim() || `${String(orig.title || orig.id).slice(0, 70)} (fork)`);
    // CROSS-STORE lineages don't exist: `sessions.parent_session_id` has a real FK to
    // sessions.id IN THE SAME STORE (verified the constraint firing on node:sqlite). A desktop
    // chat forked into the watch agent's store gets parent NULL — provenance lives in the
    // " (fork)" title and the watch's own log; pretending otherwise 503s the whole copy.
    const sameStore = sessionDbPath(profile) === sessionDbPath(toProfile);
    const overrides = {
      id: newId, title, archived: 0, auto_archived: 0, hidden: 0, pinned: 0,
      ended_at: null, end_reason: null, parent_session_id: sameStore ? sessionId : null,
    };
    // Every FK column must resolve INSIDE the target store (verified live: the agent stores have
    // `sessions.system_prompt_hash -> system_prompts.hash`, which the `default` store doesn't even
    // carry as a constraint — a copied hash is a ghost there and node:sqlite enforces FKs ON by
    // DEFAULT). parent_session_id is covered by `overrides`; the prompt hash by the drop below.
    const dropCols = new Set(['parent_session_id']);
    for (const fk of dst.prepare('PRAGMA foreign_key_list(sessions)').all()) {
      if (fk.table === 'system_prompts') dropCols.add(fk.from);
    }
    const vals = sCols.map((c) => {
      if (c in overrides) return overrides[c];
      if (!sameStore && dropCols.has(c)) return null;
      return orig[c];
    });
    dst.prepare(`INSERT INTO sessions (${sCols.map((c) => `"${c}"`).join(',')}) VALUES (${sCols.map(() => '?').join(',')})`)
      .run(...vals);
    // Messages: column intersection, fresh autoincrement ids, session re-pointed. The store's own
    // triggers index the copies; `active`/`compacted` ride along so compaction state is faithful.
    const dstMsgCols = dbCols(dst, 'messages');
    const mShared = dbCols(src, 'messages').filter((c) => dstMsgCols.includes(c) && c !== 'id');
    const rows = src.prepare(`SELECT ${mShared.map((c) => `"${c}"`).join(',')} FROM messages WHERE session_id = ? ORDER BY id`).all(sessionId);
    let copied = 0;
    if (rows.length) {
      const ins = dst.prepare(`INSERT INTO messages (${mShared.map((c) => `"${c}"`).join(',')}) VALUES (${mShared.map(() => '?').join(',')})`);
      dst.exec('BEGIN');
      for (const r of rows) {
        ins.run(...mShared.map((c) => (c === 'session_id' ? newId : r[c])));
        copied += 1;
      }
      dst.exec('COMMIT');
    }
    const out = JSON.stringify({ object: 'hermes.session.forked', id: newId, profile: toProfile, from: sessionId, copied_messages: copied, title });
    return done(res, send(res, 200, out), t0, ip, 'session_fork', 200, undefined, copied);
  } catch (e) {
    try { dst?.exec('ROLLBACK'); } catch { /* no txn */ }
    return done(res, sendErr(res, 503, `fork failed: ${String(e?.message || e)}`.slice(0, 200)), t0, ip, 'session_fork', 503);
  } finally {
    try { src?.close(); } catch { /* gone */ }
    try { dst?.close(); } catch { /* gone */ }
  }
}

// ARCHIVE / UNARCHIVE (owner batch 5: "archive instead of delete" — desktop semantics): flip the
// sessions.archived flag the sidebar and /sessions already filter on. Hide is the polite verb;
// delete stays for the sure thing. A gone session answers 200 with changed:0 (idempotent like
// delete), because the button means *get it off my list*, not *prove the row existed*.
async function handleSessionArchive(req, res, ip, body) {
  const t0 = Date.now();
  const profile = String(body.profile || 'default').trim();
  const sessionId = String(body.session_id || '').trim();
  const archived = Number(body.archived ?? 1) ? 1 : 0;
  if (!SESSIONS_PROFILE_RE.test(profile))
    return done(res, sendErr(res, 400, 'bad profile'), t0, ip, 'session_archive', 400);
  if (!SESSION_ID_RE.test(sessionId))
    return done(res, sendErr(res, 400, 'bad session id'), t0, ip, 'session_archive', 400);
  let db = null;
  try {
    db = await openStore(profile, false);
    if (!db) return done(res, sendErr(res, 404, 'store missing'), t0, ip, 'session_archive', 404);
    const r = db.prepare('UPDATE sessions SET archived = ? WHERE id = ?').run(archived, sessionId);
    const out = JSON.stringify({ object: 'hermes.session.archived', id: sessionId, profile, archived: !!archived, changed: r.changes });
    return done(res, send(res, 200, out), t0, ip, 'session_archive', 200, undefined, r.changes);
  } catch (e) {
    return done(res, sendErr(res, 503, `archive failed: ${String(e?.message || e)}`.slice(0, 200)), t0, ip, 'session_archive', 503);
  } finally {
    try { db?.close(); } catch { /* gone */ }
  }
}

async function handleSessionDelete(req, res, ip, body) {
  const t0 = Date.now();
  const profile = String(body.profile || 'default').trim();
  const sessionId = String(body.session_id || '').trim();
  if (!SESSIONS_PROFILE_RE.test(profile))
    return done(res, sendErr(res, 400, 'bad profile'), t0, ip, 'session_delete', 400);
  if (!SESSION_ID_RE.test(sessionId))
    return done(res, sendErr(res, 400, 'bad session id'), t0, ip, 'session_delete', 400);
  let db = null;
  try {
    db = await openStore(profile, false);
    if (!db) return done(res, sendErr(res, 404, 'store missing'), t0, ip, 'session_delete', 404);
    const orig = db.prepare('SELECT id FROM sessions WHERE id = ?').get(sessionId);
    if (!orig) {
      // Already gone (deleted from the desktop meanwhile) is SUCCESS for a delete button.
      return done(res, send(res, 200, JSON.stringify({ object: 'hermes.session.deleted', id: sessionId, profile, deleted: true, messages: 0 })), t0, ip, 'session_delete', 200, undefined, 0);
    }
    db.exec('BEGIN');
    const n = db.prepare('DELETE FROM messages WHERE session_id = ?').run(sessionId).changes;
    db.prepare('DELETE FROM sessions WHERE id = ?').run(sessionId);
    db.exec('COMMIT');
    const out = JSON.stringify({ object: 'hermes.session.deleted', id: sessionId, profile, deleted: true, messages: n });
    return done(res, send(res, 200, out), t0, ip, 'session_delete', 200, undefined, n);
  } catch (e) {
    try { db?.exec('ROLLBACK'); } catch { /* no txn */ }
    return done(res, sendErr(res, 503, `delete failed: ${String(e?.message || e)}`.slice(0, 200)), t0, ip, 'session_delete', 503);
  } finally {
    try { db?.close(); } catch { /* gone */ }
  }
}

const STATE_DIR = path.join(WK_DIR, '.state');
const SNAPSHOT_PATH = path.join(STATE_DIR, 'snapshot.json');
const SNAPSHOT_MAX_BYTES = 256 * 1024;
const SNAPSHOT_STALE_MS = 2 * 60 * 60 * 1000;
// Owner ask B: a 2-h-old position is a WRONG position — the location tool ages 4x faster.
const LOCATION_STALE_MS = 30 * 60 * 1000;
const TOOL_NAMES = ['watch_heart_rate', 'watch_steps', 'watch_sleep', 'watch_spo2', 'watch_location'];
fs.mkdirSync(STATE_DIR, { recursive: true });

async function writeJsonAtomic(file, obj) {
  const tmp = `${file}.${crypto.randomBytes(4).toString('hex')}.tmp`;
  await fsp.writeFile(tmp, JSON.stringify(obj, null, 1) + '\n');
  await fsp.rename(tmp, file); // same dir — atomic
}

function readSnapshot() {
  try { return JSON.parse(fs.readFileSync(SNAPSHOT_PATH, 'utf8')); } catch { return null; }
}

/** Per-tool JSON derived from the stored snapshot (shared by HTTP + MCP surfaces). */
function toolResult(name, snap, nowMs = Date.now()) {
  const base = { tool: name };
  if (!snap || typeof snap !== 'object') return { ...base, ok: false, reason: 'no snapshot stored (watch has not pushed yet)' };
  const enabled = Array.isArray(snap.enabled_tools) ? snap.enabled_tools : [];
  if (!enabled.includes(name)) return { ...base, ok: false, reason: 'tool disabled on watch (Settings ▸ Body tools)' };
  const ts = Number(snap.ts) || 0;
  const ageS = Math.max(Math.round((nowMs - ts) / 1000), 0);
  if (!ts || nowMs - ts > SNAPSHOT_STALE_MS) return { ...base, ok: false, stale: true, age_s: ageS, reason: 'snapshot older than 2h' };
  const v = snap.values && typeof snap.values === 'object' ? snap.values : {};
  switch (name) {
    case 'watch_heart_rate':
      return v.heart_rate_bpm == null ? { ...base, ok: false, reason: 'snapshot has no heart_rate_bpm' }
        : { ...base, ok: true, data: { bpm: v.heart_rate_bpm, ts } };
    case 'watch_steps':
      return v.steps_today == null ? { ...base, ok: false, reason: 'snapshot has no steps_today' }
        : { ...base, ok: true, data: { steps_today: v.steps_today, ts } };
    case 'watch_sleep': {
      const s = v.sleep && typeof v.sleep === 'object' ? v.sleep : null;
      if (!s || s.last_session_start == null) return { ...base, ok: false, reason: 'snapshot has no sleep session' };
      return { ...base, ok: true, data: {
        last_session_start: s.last_session_start,
        last_session_end: s.last_session_end ?? null,
        duration_hours: s.duration_hours ?? null,
        ts,
      } };
    }
    case 'watch_spo2':
      return v.spo2_pct == null ? { ...base, ok: false, reason: 'snapshot has no spo2_pct' }
        : { ...base, ok: true, data: { spo2_pct: v.spo2_pct, ts } };
    case 'watch_location': {
      // Freshness keys on the FIX's own ts (not the snapshot push), capped at 30 min by design.
      const l = v.location && typeof v.location === 'object' ? v.location : null;
      if (!l || l.lat == null || l.lng == null) return { ...base, ok: false, reason: 'snapshot has no location fix (watch indoors, perms off, or tool just enabled)' };
      const lts = Number(l.ts) || ts;
      const ageLoc = Math.max(Math.round((nowMs - lts) / 1000), 0);
      if (nowMs - lts > LOCATION_STALE_MS) return { ...base, ok: false, stale: true, age_s: ageLoc, reason: 'location fix older than 30 min' };
      return { ...base, ok: true, data: { lat: l.lat, lng: l.lng, accuracy_m: l.accuracy_m ?? null, ts: lts, age_s: ageLoc } };
    }
    default:
      return { ...base, ok: false, reason: 'unknown tool' };
  }
}

// ── helpers ────────────────────────────────────────────────────────────────
const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');

function timingSafeEqual(a, b) {
  const ba = Buffer.from(a), bb = Buffer.from(b);
  return ba.length === bb.length && crypto.timingSafeEqual(ba, bb);
}

function logLine(fields) {
  const line = JSON.stringify({ ts: new Date().toISOString(), ...fields }) + '\n';
  if (MCP_MODE) process.stderr.write(line); // stdio MCP: stdout is JSON-RPC only
  else process.stdout.write(line);
}

function send(res, status, body, headers = {}) {
  const isBuf = Buffer.isBuffer(body);
  res.writeHead(status, {
    'Content-Type': isBuf ? 'application/octet-stream' : 'application/json; charset=utf-8',
    'Content-Length': isBuf ? body.length : Buffer.byteLength(String(body)),
    ...headers,
  });
  res.end(body);
}
const sendErr = (res, status, msg) => send(res, status, JSON.stringify({ error: msg }));

function readBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > maxBytes) { reject(Object.assign(new Error('payload too large'), { status: 413 })); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function runChild(cmd, args, opts) {
  return new Promise((resolve) => {
    const child = spawn(cmd, args, { ...opts, stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '', err = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { err += d; });
    const killer = setTimeout(() => child.kill('SIGKILL'), opts.timeout ?? 60_000);
    child.on('error', (e) => { clearTimeout(killer); resolve({ code: -1, out, err: err + '\nspawn failed: ' + e.message, killed: false, spawnError: true }); });
    child.on('close', (code, signal) => { clearTimeout(killer); resolve({ code, out, err, killed: signal != null }); });
  });
}

// Minimal multipart/form-data extraction: returns {content, filename, contentType} of field `name`.
function extractMultipartFile(buf, contentType, name) {
  const m = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType || '');
  if (!m) return null;
  const boundary = Buffer.from(`--${m[1] || m[2].trim()}`);
  let pos = buf.indexOf(boundary);
  while (pos !== -1) {
    const partStart = pos + boundary.length;
    if (buf.slice(partStart, partStart + 2).toString() === '--') break; // final boundary
    const head = buf.indexOf('\r\n\r\n', partStart);
    const next = buf.indexOf(boundary, partStart);
    if (head === -1 || next === -1) break;
    const headers = buf.slice(partStart, head).toString('latin1');
    // body ends before the \r\n that precedes the next boundary
    const body = buf.slice(head + 4, Math.max(head + 4, next - 2));
    const dm = /name="([^"]*)"/i.exec(headers);
    if (dm && dm[1] === name) {
      const fm = /filename="([^"]*)"/i.exec(headers);
      return { content: body, filename: fm ? fm[1] : '', contentType: (/content-type:\s*([^\r\n]+)/i.exec(headers) || [])[1] || '' };
    }
    pos = next;
  }
  return null;
}

// ── /render/mermaid ────────────────────────────────────────────────────────
/** Graphviz-ish `digraph{a->b}` (what the dev render_test sends) isn't a diagram type in
 *  mermaid-cli — rewrite a single-statement digraph into `flowchart TD` edges. */
function normalizeMermaid(code) {
  const m = /^\s*digraph\s*\w*\s*\{([\s\S]*)\}\s*$/.exec(code);
  if (!m) return code;
  const edges = m[1].split(/[;\n]+/).map(s => s.trim()).filter(Boolean)
    .map(e => '  ' + e.replace(/-{1,2}>/g, '-->'));
  return edges.length ? ['flowchart TD', ...edges].join('\n') : code;
}

async function handleRender(req, res, ip) {
  const t0 = Date.now();
  let body;
  try { body = JSON.parse((await readBody(req, JSON_MAX_BYTES)).toString('utf8')); }
  catch (e) { return done(res, sendErr(res, e.status || 400, e.status ? 'body >1MB' : 'invalid JSON body'), t0, ip, 'render', e.status || 400); }
  const code = typeof body.code === 'string' ? normalizeMermaid(body.code.trim()) : '';
  if (!code) return done(res, sendErr(res, 400, 'missing "code"'), t0, ip, 'render', 400);
  const width = Math.min(1200, Math.max(100, Number(body.width) || 460));
  // mmdc v12 outputs png/svg/pdf only — png it is (no webp).
  const key = sha256(`${code}|${width}`);
  const cachePath = path.join(CACHE_DIR, `${key}.png`);

  if (fs.existsSync(cachePath)) {
    const img = fs.readFileSync(cachePath);
    return done(res, send(res, 200, img, { 'Content-Type': 'image/png', 'X-Cache': 'hit' }), t0, ip, 'render', 200, 'hit', img.length);
  }

  const dir = fs.mkdtempSync(path.join(TMP_DIR, 'render-'));
  try {
    const inFile = path.join(dir, 'diagram.mmd');
    const outFile = path.join(dir, 'diagram.png');
    await fsp.writeFile(inFile, code + '\n');
    const r = await runChild('npx', ['-y', '@mermaid-js/mermaid-cli', '-i', inFile, '-o', outFile,
      '--size', String(width), '-s', '2', '-p', PUPPETEER_CFG],
      { cwd: dir, env: CHILD_ENV, timeout: RENDER_TIMEOUT_MS });
    if (r.code !== 0 || !fs.existsSync(outFile)) {
      const excerpt = (r.err || 'mmdc produced no output').slice(-1200);
      const status = r.killed ? 504 : 400; // timeout vs mermaid parse/render error
      return done(res, sendErr(res, status, r.killed ? 'render timeout (60s)' : excerpt), t0, ip, 'render', status, 'miss');
    }
    await fsp.rename(outFile, cachePath); // same fs (.cache) — atomic
    const img = await fsp.readFile(cachePath);
    return done(res, send(res, 200, img, { 'Content-Type': 'image/png', 'X-Cache': 'miss' }), t0, ip, 'render', 200, 'miss', img.length);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

// ── /img ─────────────────────────────────────────────────────────────────────
// Image scaler (F6): the gateway emits `![desc](/abs/path.png)` lines and the watch fetches
// them here. Boundaries: the extension whitelist (only image types, never .env/.py/…), the
// 15 MB cap, and realpath re-checks (a symlink under a root may not escape it). Roots:
// the daemon user's HOME is ALWAYS allowed (batch-6 #26: the comfy agent writes under
// /run/media/…/comfy/outputs, outside the legacy four-path allowlist — desktop showed the
// photo, the wrist 403'd it). This daemon already hands out every session BODY via
// /sessions and /search, so image-typed files under the owner's home are strictly less
// sensitive than what the same bearer already reads; extra mounts go in img_roots.
const IMG_MAX_INPUT_BYTES = 15 * 1024 * 1024;
const IMG_EXT = new Set(['.png', '.jpg', '.jpeg', '.webp', '.gif', '.bmp']);
// Full-res passthrough (owner batch 2: "images should be in full res to the watch; scaling should
// happen on the watch"): originals are byte-identical only for these types — gif/bmp fall back to
// the legacy scaler (animation/mode conversion genuinely needs Pillow).
const IMG_FULL_MIME = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp' };
const IMG_SCALER = path.join(WK_DIR, 'img_scale.py');
const IMG_ROOTS = [os.homedir(), '/tmp', '/var/tmp',
  ...(Array.isArray(cfg.img_roots) ? cfg.img_roots : [])]
  .map(r => { try { return fs.realpathSync(path.resolve(String(r))); } catch { return null; } })
  .filter(Boolean);
const underRoots = (p) => IMG_ROOTS.some(root => p === root || p.startsWith(root + path.sep));

async function handleImg(req, res, ip, params) {
  const t0 = Date.now();
  const p = typeof params.path === 'string' ? params.path.trim() : '';
  if (!p) return done(res, sendErr(res, 400, 'missing "path"'), t0, ip, 'img', 400);
  // width OPTIONAL (owner batch 2): missing/empty/0 ⇒ full-res original bytes for png/jpeg/webp.
  // Any width>0 (and non-empty junk → 460) runs the legacy scaler EXACTLY as before.
  const fullMode = Number(params.width ?? 0) === 0;
  const width = Math.min(1200, Math.max(100, Number(params.width) || 460));
  const resolved = path.resolve(p);
  // Containment BEFORE existence: outside-roots is always 403, never a "file exists?" probe.
  // The path is the whole diagnosis for a 403 (owner bug 2026-11-02: wrist said "image 403"
  // with no way to see WHICH path) — log it; the caller already knew this path.
  if (!underRoots(resolved)) {
    console.log(JSON.stringify({ ts: new Date().toISOString(), ev: "img403", ip, path: resolved }));
    return done(res, sendErr(res, 403, "path outside img_roots"), t0, ip, "img", 403);
  }
  let real;
  try { real = fs.realpathSync(resolved); }
  catch (e) {
    const notFound = e.code === 'ENOENT';
    return done(res, sendErr(res, notFound ? 404 : 400, notFound ? 'not found' : 'cannot resolve path'), t0, ip, 'img', notFound ? 404 : 400);
  }
  if (!underRoots(real)) return done(res, sendErr(res, 403, 'symlink escapes img_roots'), t0, ip, 'img', 403);
  if (!IMG_EXT.has(path.extname(real).toLowerCase()))
    return done(res, sendErr(res, 415, 'extension not allowed (png/jpg/jpeg/webp/gif/bmp)'), t0, ip, 'img', 415);
  const st = fs.statSync(real); // race with realpath is fine: worst case a later read 404s via scaler
  if (!st.isFile()) return done(res, sendErr(res, 400, 'not a file'), t0, ip, 'img', 400);
  if (st.size > IMG_MAX_INPUT_BYTES)
    return done(res, sendErr(res, 413, `image >${Math.round(IMG_MAX_INPUT_BYTES / 1048576)}MB`), t0, ip, 'img', 413);

  if (fullMode) {
    const ext = path.extname(real).toLowerCase();
    const mime = IMG_FULL_MIME[ext];
    if (mime) {
      // Serve the source file VERBATIM (byte-identical to disk — no Pillow, no WebP re-encode).
      // Key parity with the scaler cache: realpath|mtime|width where width=full.
      try {
        const img = fs.readFileSync(real);
        const key = sha256(`${real}|${st.mtimeMs}|full`);
        return done(res, send(res, 200, img,
          { 'Content-Type': mime, 'X-Cache': `raw-${key.slice(0, 12)}` }), t0, ip, 'img', 200, 'raw', img.length);
      } catch (e) {
        return done(res, sendErr(res, 500, `read failed: ${e.message}`.slice(0, 200)), t0, ip, 'img', 500);
      }
    }
    // gif/bmp asked for full but genuinely need the scaler → legacy 460 below (documented).
  }

  const key = sha256(`${real}|${st.mtimeMs}|${width}`);
  const cachePath = path.join(CACHE_DIR, `${key}.webp`);
  if (fs.existsSync(cachePath)) {
    let note = null;
    try { note = fs.readFileSync(`${cachePath}.note`, 'utf8') || null; } catch { /* no note — plain image */ }
    const img = fs.readFileSync(cachePath);
    return done(res, send(res, 200, img,
      { 'Content-Type': 'image/webp', 'X-Cache': 'hit', ...(note ? { 'X-Note': note } : {}) }), t0, ip, 'img', 200, 'hit', img.length);
  }

  if (!fs.existsSync(VENV_PY) || !fs.existsSync(IMG_SCALER))
    return done(res, sendErr(res, 503, 'img scaler missing — run scripts/whisper-env.sh'), t0, ip, 'img', 503);
  const tmp = path.join(TMP_DIR, `img-${crypto.randomBytes(6).toString('hex')}.webp`);
  try {
    // Single-threaded by design: watch images are occasional and Pillow scales in ms.
    const r = await runChild(VENV_PY, [IMG_SCALER, real, tmp, String(width)], { cwd: WK_DIR, env: CHILD_ENV, timeout: IMG_TIMEOUT_MS });
    if (r.killed) return done(res, sendErr(res, 504, 'scale timeout (30s)'), t0, ip, 'img', 504);
    if (r.code !== 0) {
      const brace = r.out.indexOf('{'); // python exit 3 = undecodable file / pixel bomb → 415
      const status = r.code === 3 ? 415 : 500;
      const msg = (brace >= 0 ? r.out.slice(brace) : (r.err || 'scale failed')).slice(-1200);
      return done(res, sendErr(res, status, msg), t0, ip, 'img', status, 'miss');
    }
    const brace = r.out.indexOf('{');
    const meta = brace === -1 ? {} : JSON.parse(r.out.slice(brace));
    if (meta.note) fs.writeFileSync(`${cachePath}.note`, meta.note);
    await fsp.rename(tmp, cachePath); // same fs (.cache) — atomic
    const img = await fsp.readFile(cachePath);
    return done(res, send(res, 200, img,
      { 'Content-Type': 'image/webp', 'X-Cache': 'miss', ...(meta.note ? { 'X-Note': meta.note } : {}) }), t0, ip, 'img', 200, 'miss', img.length);
  } finally {
    fs.rmSync(tmp, { force: true });
  }
}

// ── /media ─────────────────────────────────────────────────────────────────
// A/V streaming (owner 2026-11-02: "the desktop shows generated video/audio — the wrist should
// too, but there's no reason for a big-ass video over the wire"). ffmpeg pre-shrinks to a watch
// shape (H.264 mp4 +faststart / AAC m4a), cached by realpath|mtime|params, served with HTTP
// Range so ExoPlayer can seek. The WATCH picks the shape: q=0..100 quality slider,
// mono=1 (watch speaker default; BT headphones ⇒ stereo), ab=audio kbit/s.
// Modes: play (default) → mp4/m4a · poster=1 → one JPEG frame for the inline card ·
//        probe=1 → JSON {duration_s,width,height} (inline card meta, no transcode).
const MEDIA_MAX_INPUT_BYTES = 200 * 1024 * 1024;
const MEDIA_TIMEOUT_MS = 5 * 60_000;      // full transcode hard cap
const POSTER_TIMEOUT_MS = 30_000;
const VIDEO_EXT = new Set(['.mp4', '.mov', '.m4v', '.mkv', '.webm', '.avi', '.3gp', '.ts']);
const AUDIO_EXT = new Set(['.mp3', '.m4a', '.aac', '.wav', '.ogg', '.opus', '.flac', '.wma', '.amr']);
const MEDIA_DIR = MEDIA_DIR_EARLY;
const FFMPEG = cfg.ffmpeg_path || 'ffmpeg';
const FFPROBE = cfg.ffprobe_path || 'ffprobe';
// Watch faces top out at 480×480; presets ladder down from there for bandwidth.
const VIDEO_PRESETS = [
  { max: 360, vbit: 350_000, fps: 24 },   // q < 34
  { max: 480, vbit: 700_000, fps: 30 },   // q < 67 (default)
  { max: 540, vbit: 1_200_000, fps: 30 }, // q ≥ 67
];
const videoPreset = (q) => VIDEO_PRESETS[q < 34 ? 0 : q < 67 ? 1 : 2];
const mediaInflight = new Map(); // cacheKey → Promise<{file,type}>; joiners share one transcode

/** ffprobe JSON (duration/streams); null on any failure — callers treat meta as optional. */
async function ffprobeMeta(file) {
  const r = await runChild(FFPROBE, [
    '-v', 'error', '-show_entries', 'format=duration:stream=width,height',
    '-of', 'json', file,
  ], { timeout: 15_000 });
  if (r.code !== 0) return null;
  try {
    const j = JSON.parse(r.out || '{}');
    const v = (j.streams || []).find((s) => s.width) || {};
    return {
      duration_s: Math.round(Number(j.format?.duration) || 0),
      width: v.width || null, height: v.height || null,
    };
  } catch { return null; }
}

async function runMediaFfmpeg(args, timeout) {
  const r = await runChild(FFMPEG, ['-hide_banner', '-loglevel', 'error', '-nostdin', ...args], { timeout });
  return r;
}

/** Build (or join) the cached derivative. Throws {status,msg} for HTTP mapping. */
async function mediaDerivative(real, stKey, mode, q, mono, ab) {
  const key = sha256(`${real}|${stKey}|${mode}|${mode === 'play' ? q : '-'}|${mode === 'play' ? mono : '-'}|${mode === 'play' ? ab : '-'}`);
  const extOut = mode === 'poster' ? '.jpg' : VIDEO_EXT.has(path.extname(real).toLowerCase()) ? '.mp4' : '.m4a';
  const file = path.join(MEDIA_DIR, key + extOut);
  if (fs.existsSync(file)) return { file, type: mode === 'poster' ? 'image/jpeg' : extOut === '.mp4' ? 'video/mp4' : 'audio/mp4', cached: true };
  if (mediaInflight.has(key)) return mediaInflight.get(key);

  const job = (async () => {
    const tmp = path.join(TMP_DIR, `media-${crypto.randomBytes(6).toString('hex')}${extOut}`);
    try {
      let r;
      if (mode === 'poster') {
        const meta = await ffprobeMeta(real);
        const at = meta && meta.duration_s > 2 ? Math.min(10, Math.floor(meta.duration_s / 3)) : 0;
        const seek = at > 0 ? ['-ss', String(at)] : [];
        r = await runMediaFfmpeg([...seek, '-i', real, '-frames:v', '1',
          '-vf', 'scale=240:-2', '-q:v', '5', '-y', tmp], POSTER_TIMEOUT_MS);
        if (r.code !== 0 && at > 0) r = await runMediaFfmpeg(['-i', real, '-frames:v', '1',
          '-vf', 'scale=240:-2', '-q:v', '5', '-y', tmp], POSTER_TIMEOUT_MS);
      } else if (VIDEO_EXT.has(path.extname(real).toLowerCase())) {
        const p = videoPreset(q);
        r = await runMediaFfmpeg(['-i', real,
          '-vf', `scale='min(${p.max},iw)':'min(${p.max},ih)':force_original_aspect_ratio=decrease:force_divisible_by=2,fps=${p.fps}`,
          '-c:v', 'libx264', '-preset', 'veryfast', '-profile:v', 'main', '-pix_fmt', 'yuv420p',
          '-b:v', String(p.vbit), '-maxrate', String(Math.round(p.vbit * 1.5)), '-bufsize', String(p.vbit * 2),
          '-c:a', 'aac', '-b:a', `${ab}k`, '-ac', String(mono ? 1 : 2),
          '-movflags', '+faststart', '-y', tmp], MEDIA_TIMEOUT_MS);
      } else {
        r = await runMediaFfmpeg(['-i', real, '-vn', '-c:a', 'aac', '-b:a', `${ab}k`, '-ac', String(mono ? 1 : 2), '-y', tmp], MEDIA_TIMEOUT_MS);
      }
      if (r.killed) throw { status: 504, msg: `${mode} timeout — ${mode === 'poster' ? '30s' : '300s'}` };
      if (r.code !== 0) throw { status: 500, msg: ('ffmpeg failed: ' + (r.err || '')).slice(-600) };
      await fsp.rename(tmp, file); // same fs (.cache) — atomic
      return { file, type: mode === 'poster' ? 'image/jpeg' : extOut === '.mp4' ? 'video/mp4' : 'audio/mp4', cached: false };
    } finally {
      fs.rmSync(tmp, { force: true });
      mediaInflight.delete(key);
    }
  })();
  mediaInflight.set(key, job);
  return job;
}

/** Stream with HTTP Range support (ExoPlayer seek). */
function sendFile(req, res, file, type, extra = {}) {
  const st = fs.statSync(file);
  const range = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || '');
  let start = 0, end = st.size - 1, partial = false;
  if (range && (range[1] || range[2])) {
    partial = true;
    if (range[1]) { start = Math.min(Number(range[1]), Math.max(0, st.size - 1)); if (range[2]) end = Math.min(Number(range[2]), st.size - 1); }
    else { start = Math.max(0, st.size - Number(range[2])); } // suffix form: bytes=-N
    if (start > end) { res.writeHead(416, { 'Content-Range': `bytes */${st.size}` }); res.end(); return; }
  }
  res.writeHead(partial ? 206 : 200, {
    'Content-Type': type, 'Content-Length': end - start + 1,
    ...(partial ? { 'Content-Range': `bytes ${start}-${end}/${st.size}` } : {}),
    'Accept-Ranges': 'bytes', ...extra,
  });
  if (req.method === 'HEAD') { res.end(); return; }
  fs.createReadStream(file, { start, end }).pipe(res);
}

async function handleMedia(req, res, ip, params) {
  const t0 = Date.now();
  const p = String(params.path || '').trim();
  if (!p) return done(res, sendErr(res, 400, 'missing "path"'), t0, ip, 'media', 400);
  const mode = params.poster === '1' ? 'poster' : params.probe === '1' ? 'probe' : 'play';
  const q = Math.max(0, Math.min(100, Number(params.q ?? 50) || 0));
  const mono = String(params.mono ?? '1') !== '0';
  const ab = Math.max(24, Math.min(192, Number(params.ab ?? 64) || 64));

  const resolved = path.resolve(p);
  if (!underRoots(resolved)) {
    console.log(JSON.stringify({ ts: new Date().toISOString(), ev: 'media403', ip, path: resolved }));
    return done(res, sendErr(res, 403, 'path outside img_roots'), t0, ip, 'media', 403);
  }
  let real;
  try { real = fs.realpathSync(resolved); } catch { return done(res, sendErr(res, 404, 'file not found'), t0, ip, 'media', 404); }
  if (!underRoots(real)) return done(res, sendErr(res, 403, 'symlink escapes img_roots'), t0, ip, 'media', 403);
  const ext = path.extname(real).toLowerCase();
  if (!VIDEO_EXT.has(ext) && !AUDIO_EXT.has(ext))
    return done(res, sendErr(res, 415, 'not a media extension'), t0, ip, 'media', 415);
  const st = fs.statSync(real);
  if (!st.isFile()) return done(res, sendErr(res, 400, 'not a file'), t0, ip, 'media', 400);
  if (st.size > MEDIA_MAX_INPUT_BYTES)
    return done(res, sendErr(res, 413, `media >${Math.round(MEDIA_MAX_INPUT_BYTES / 1048576)}MB`), t0, ip, 'media', 413);

  if (mode === 'probe') {
    const meta = await ffprobeMeta(real);
    if (!meta) return done(res, sendErr(res, 500, 'ffprobe failed'), t0, ip, 'media', 500);
    return done(res, send(res, 200, JSON.stringify({ ...meta, kind: VIDEO_EXT.has(ext) ? 'video' : 'audio' })), t0, ip, 'media', 200);
  }
  try {
    const { file, type, cached } = await mediaDerivative(real, String(st.mtimeMs), mode, q, mono, ab);
    const tBytes = { bytes: 0 };
    done(res, sendFile(req, res, file, type, { 'X-Cache': cached ? 'hit' : 'miss' }), t0, ip, 'media', 200, cached ? 'hit' : 'miss', fs.statSync(file).size);
    return tBytes;
  } catch (e) {
    return done(res, sendErr(res, e.status || 500, String(e.msg || e).slice(0, 300)), t0, ip, 'media', e.status || 500);
  }
}

// ── /stt/whisper ───────────────────────────────────────────────────────────
async function handleStt(req, res, ip) {
  const t0 = Date.now();
  if (!fs.existsSync(VENV_PY)) return done(res, sendErr(res, 503, 'whisper venv missing — run scripts/whisper-env.sh'), t0, ip, 'stt', 503);
  let buf;
  try { buf = await readBody(req, AUDIO_MAX_BYTES); }
  catch (e) { return done(res, sendErr(res, 413, 'audio >10MB'), t0, ip, 'stt', 413); }
  const file = extractMultipartFile(buf, req.headers['content-type'], 'audio');
  if (!file || file.content.length === 0) return done(res, sendErr(res, 400, 'missing multipart field "audio"'), t0, ip, 'stt', 400);
  const ext = (path.extname(file.filename || '') || (file.contentType.includes('webm') ? '.webm' : '.wav')).slice(0, 8).replace(/[^.a-z0-9]/gi, '');
  const tmp = path.join(TMP_DIR, `stt-${crypto.randomBytes(6).toString('hex')}${ext}`);
  try {
    await fsp.writeFile(tmp, file.content);
    // COLD START by design in v1: python spins up + loads "base" each call (~2-4s). TODO: warm worker.
    const r = await runChild(VENV_PY, [TRANSCRIBER, tmp], { cwd: WK_DIR, env: CHILD_ENV, timeout: STT_TIMEOUT_MS });
    if (r.killed) return done(res, sendErr(res, 504, 'transcribe timeout (55s)'), t0, ip, 'stt', 504);
    if (r.code !== 0) return done(res, sendErr(res, 500, (r.err || 'transcribe failed').slice(-1200)), t0, ip, 'stt', 500);
    const brace = r.out.indexOf('{');
    if (brace === -1) return done(res, sendErr(res, 500, 'no JSON from transcriber'), t0, ip, 'stt', 500);
    const text = JSON.parse(r.out.slice(brace)).text ?? '';
    return done(res, send(res, 200, JSON.stringify({ text })), t0, ip, 'stt', 200, undefined, file.content.length);
  } finally {
    fs.rmSync(tmp, { force: true });
  }
}

// ── /sessions (cross-profile session list for the watch's Sessions screen) ──
// Owner walkthrough: "continue my computer chats from the watch" needs DESKTOP sessions (profile
// `default`), not just the current agent's. The dashboard serves them at GET /api/sessions but its
// basic-auth is an scrypt hash (no plaintext to proxy with) — so, since watchkit is on the same
// box, read the SAME SQLite store the dashboard reads (default profile: <home>/state.db; others:
// <profiles_dir>/<p>/state.db, layout confirmed in hermes_cli/web_server_sessions.py
// _session_db_path_for_profile + hermes_state_sessions.list_sessions_rich). Response mirrors the
// gateway's `/api/sessions` shape so the app's GatewaySessions.parse handles both sources.
const HERMES_HOME = process.env.WATCHKIT_HERMES_HOME || path.dirname(PROFILES_DIR);
const SESSIONS_PROFILE_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/; // same grammar as the app's ProfileLogic
const SESSIONS_LIMIT_MAX = 30;
// Epoch-seconds sanity window: a garbage double salvaged from a damaged page must not pin
// recency at 5e246 (hermes_state_common._sql_freshest_of guards the same thing in SQL).
const saneEpochSec = (v) => { const n = Number(v); return Number.isFinite(n) && n > 1e9 && n < 4e9 ? n : 0; };

function sessionDbPath(profile) {
  // `default` is the multiplexed home profile: its store is <home>/state.db, NOT profiles/default/.
  if (profile === 'default') return path.join(HERMES_HOME, 'state.db');
  const dir = path.resolve(PROFILES_DIR, profile);
  if (!dir.startsWith(path.resolve(PROFILES_DIR) + path.sep)) return null; // traversal guard, belt+suspenders
  return path.join(dir, 'state.db');
}

async function handleSessions(req, res, ip, params) {
  const t0 = Date.now();
  const profile = String(params.profile ?? 'default').trim() || 'default';
  if (!SESSIONS_PROFILE_RE.test(profile))
    return done(res, sendErr(res, 400, 'bad profile name'), t0, ip, 'sessions', 400);
  const limit = Math.min(SESSIONS_LIMIT_MAX, Math.max(1, Number(params.limit) || 20));
  const dbPath = sessionDbPath(profile);
  if (!dbPath || !fs.existsSync(dbPath)) {
    // Fresh/never-used profile: an empty list beats an error — the chip still renders.
    const empty = JSON.stringify({ object: 'list', data: [], profile, limit, offset: 0, has_more: false });
    return done(res, send(res, 200, empty), t0, ip, 'sessions', 200, undefined, 0);
  }
  let db;
  try {
    // Lazy import: node:sqlite needs node ≥22.5; older daemons degrade this ONE endpoint (503)
    // instead of failing to boot — /health /agents /img /render /stt all keep working.
    const { DatabaseSync } = await import('node:sqlite');
    db = new DatabaseSync(dbPath, { readOnly: true });
    // Desktop-sidebar semantics (web_routers/sessions.get_sessions defaults): archived + hidden out,
    // cron/tool/subagent noise out, compression continuations folded behind their root (the root's
    // own recency is chain-maxed via message timestamps below). `?archived=1` INVERTS the archived
    // term — the watch's 🗃 chip lists what the sidebar hides (same noise rules, same profile).
    const wantArchived = String(params.archived ?? '0') === '1';
    const rows = db.prepare(`
      SELECT s.id AS id, s.title AS title, s.message_count AS message_count,
             s.started_at AS started_at, s.last_activity_at AS last_activity_at,
             (SELECT MAX(m.timestamp) FROM messages m WHERE m.session_id = s.id) AS msg_ts
      FROM sessions s
      WHERE s.hidden = 0 AND s.archived = ?
        AND COALESCE(s.source, '') NOT IN ('cron', 'tool', 'subagent')
        AND NOT (s.parent_session_id IS NOT NULL AND EXISTS (
          SELECT 1 FROM sessions p WHERE p.id = s.parent_session_id AND p.end_reason = 'compression'
            AND json_extract(COALESCE(s.model_config, '{}'), '$._branched_from') IS NULL
            AND json_extract(COALESCE(s.model_config, '{}'), '$._delegate_from') IS NULL))
      ORDER BY COALESCE(s.last_activity_at, s.started_at) DESC
      LIMIT ?
    `).all(wantArchived ? 1 : 0, limit);
    const data = rows.map((r) => ({
      id: String(r.id),
      title: String(r.title ?? ''),
      last_active: Math.max(saneEpochSec(r.msg_ts), saneEpochSec(r.last_activity_at), saneEpochSec(r.started_at)),
      message_count: Number(r.message_count) || 0,
      profile,
      ...(wantArchived ? { archived: true } : {}),
    })).sort((a, b) => b.last_active - a.last_active);
    const body = JSON.stringify({ object: 'list', data, profile, limit, offset: 0, has_more: false });
    return done(res, send(res, 200, body), t0, ip, 'sessions', 200, undefined, data.length);
  } catch (e) {
    // Busy/corrupt store is unavailable, not empty (same 503 posture the dashboard takes).
    return done(res, sendErr(res, 503, `sessions store unavailable: ${String(e?.message || e)}`.slice(0, 200)), t0, ip, 'sessions', 503);
  } finally {
    try { db?.close(); } catch { /* already gone */ }
  }
}

// ── /approvals_waiting (batch-6 #25: desktop-session life reaches the wrist) ───
// Owner 2026-10-30: "I'm not getting notifications or approvals from sessions started on the
// desktop — only stuff I start on the watch. I want desktop-session notifications — even ones
// never opened on the watch — so when I step away from the computer I still get them; the ONLY
// silence allowed is the session that is actually the top thing on my watch's chat screen."
// A desktop run has no watch-side stream, so nothing ever pushed it. Design review found the
// honest truth source in the profile's state.db (the gateway keeps it live, read-only for us —
// same posture as /sessions and /search):
//   • WAITING approvals — the approval park heartbeats `sessions.last_activity_description =
//     "waiting for user approval"` every ~10 s (agent activity tracker; approval_gateway_wait
//     activity_heartbeat), so a session with that beacon AND a fresh last_activity_at is parked
//     RIGHT NOW. What it wants approved: the session's last assistant message's tool_calls
//     (persisted before execution — that IS the parked call). No request_id on disk, and desk
//     parks answer only on their own ws lane, so the wrist notification opens the session
//     rather than approving inline (watch-started runs keep their inline Approve/Deny).
//   • PULSES — visible session rows (same visibility rules as /sessions) with message_count +
//     last_role: the watch diffs counts between polls and buzzes once when an assistant answer
//     FINISHED on the desktop since the last look.
// Per-profile errors blank only their own profile. The watch (domain/AwayWatch) dedups and
// applies the viewing suppression client-side.
//   GET /approvals_waiting → {"object":"list",
//     "waiting":[{"profile","chat_id","title","tool","arguments","parked_at"}],
//     "pulses":[{"profile","id","title","message_count","last_role","last_active"}]}
const PARK_FRESH_SEC = 15 * 60;       // the park heartbeats every ~10 s; stale beacon = gone
const PULSE_FRESH_SEC = 30 * 60;      // a session idle for hours cannot have just finished
const PULSE_LIMIT = 12;               // per profile: nothing older matters inside 30 min

function parseToolCallNames(toolCallsJson) {
  try {
    const tcs = JSON.parse(toolCallsJson);
    if (!Array.isArray(tcs)) return [];
    return tcs.map((tc) => ({
      tool: String(tc?.function?.name ?? tc?.name ?? ''),
      arguments: String(tc?.function?.arguments ?? tc?.arguments ?? ''),
    })).filter((t) => t.tool);
  } catch { return []; }
}

async function handleApprovalsWaiting(req, res, ip) {
  const t0 = Date.now();
  seedProfileKeys(false); // throttled auto-key sweep: brand-new agents join the key map within a
  // minute of appearing, driven by the watch's own 20 s away-poll — no human step (owner 2026-11-02).
  const waiting = [];
  const pulses = [];
  let names = [];
  try {
    names = fs.readdirSync(PROFILES_DIR, { withFileTypes: true })
      .filter((e) => e.isDirectory() && SESSIONS_PROFILE_RE.test(e.name)).map((e) => e.name);
  } catch { /* no profiles dir: honest empty */ }
  // The default profile's store is the HOME state.db (desktop sessions live there), not
  // profiles/default/state.db — same resolution /sessions uses.
  if (!names.includes('default')) names.unshift('default'); // home profile: no directory, but it owns the desktop sessions
  const stores = names.map((n) => ({ name: n, file: sessionDbPath(n) })).filter((s) => s.file && fs.existsSync(s.file));
  for (const { name, file } of stores) {
    let db;
    try {
      const { DatabaseSync } = await import('node:sqlite'); // lazy — same 503 posture as /sessions
      db = new DatabaseSync(file, { readOnly: true });
      const nowSec = Math.floor(Date.now() / 1000);
      const parked = db.prepare(`
        SELECT id, title, last_activity_at FROM sessions
        WHERE last_activity_description LIKE 'waiting for user approval%'
          AND COALESCE(last_activity_at, started_at) > ?
        ORDER BY last_activity_at DESC LIMIT 8
      `).all(nowSec - PARK_FRESH_SEC);
      for (const r of parked) {
        let tool = '', args = '';
        try {
          const m = db.prepare(
            "SELECT tool_calls FROM messages WHERE session_id = ? AND tool_calls IS NOT NULL AND tool_calls != '' ORDER BY id DESC LIMIT 1"
          ).get(r.id);
          const calls = m ? parseToolCallNames(m.tool_calls) : [];
          tool = calls.map((c) => c.tool).join(', ');
          args = (calls[0]?.arguments || '').slice(0, 240);
        } catch { /* payload is garnish: the beacon itself is the news */ }
        waiting.push({
          profile: name, chat_id: String(r.id), title: String(r.title || ''),
          tool, arguments: args, parked_at: Number(r.last_activity_at) || 0,
        });
      }
      const since = nowSec - PULSE_FRESH_SEC;
      for (const r of db.prepare(`
        SELECT s.id AS id, s.title AS title, s.message_count AS mc,
               (SELECT MAX(m.timestamp) FROM messages m WHERE m.session_id = s.id) AS msg_ts,
               (SELECT m.role FROM messages m WHERE m.session_id = s.id
                 ORDER BY m.timestamp DESC, m.id DESC LIMIT 1) AS last_role
        FROM sessions s
        WHERE s.hidden = 0 AND s.archived = 0
          AND COALESCE(s.source, '') NOT IN ('cron', 'tool', 'subagent')
          AND COALESCE(s.last_activity_at, s.started_at) > ?
        ORDER BY COALESCE(s.last_activity_at, s.started_at) DESC
        LIMIT ?
      `).all(since, PULSE_LIMIT)) {
        const lastActive = Math.max(Number(r.msg_ts) || 0, Number(r.last_activity_at) || 0);
        if (lastActive < since) continue;
        pulses.push({
          profile: name, id: String(r.id), title: String(r.title || ''),
          message_count: Number(r.mc) || 0, last_role: String(r.last_role || ''),
          last_active: lastActive,
        });
      }
    } catch { /* one locked/corrupt store blanks its own profile, never the batch */ }
    finally { try { db?.close(); } catch { /* already gone */ } }
  }
  waiting.sort((a, b) => b.parked_at - a.parked_at);
  pulses.sort((a, b) => b.last_active - a.last_active);
  const body = JSON.stringify({ object: 'list', waiting, pulses });
  return done(res, send(res, 200, body), t0, ip, 'approvals_waiting', 200, undefined,
    waiting.length + pulses.length);
}

// ── /session_messages (history backfill on adoption) ────────────────────────
// Owner bug: adopting a `default`-profile session from the watch's Sessions screen set the
// session id but opened EMPTY — the transcript lives only in the profile's state.db. Same
// read-the-store-directly approach as /sessions above (see its header for why). The app's
// SessionHistory parser owns the mapping to chat rows; this endpoint just hands over the
// CLEANED transcript: user/assistant turns only, active (un-compacted) rows, non-empty
// content, compression-summary rows kept as role "summary", process/system display_kinds
// dropped. NEWEST [limit] rows, returned oldest-first (a 300-msg chat opens on its tail).
const MSGS_LIMIT_MAX = 120;
// Owner: "long replies are getting cut off — we need the full replies." The old 2 KB wall ate
// every real answer. 20 KB renders any honest chat turn in full and still refuses a log dump.
const MSGS_CONTENT_MAX = 20_000;
const SESSION_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$/;

async function handleSessionMessages(req, res, ip, params) {
  const t0 = Date.now();
  const profile = String(params.profile ?? 'default').trim() || 'default';
  const sessionId = String(params.session_id ?? '').trim();
  const limit = Math.min(MSGS_LIMIT_MAX, Math.max(1, Number(params.limit) || MSGS_LIMIT_MAX));
  if (!SESSIONS_PROFILE_RE.test(profile))
    return done(res, sendErr(res, 400, 'bad profile name'), t0, ip, 'session_messages', 400);
  if (!SESSION_ID_RE.test(sessionId))
    return done(res, sendErr(res, 400, 'bad session_id'), t0, ip, 'session_messages', 400);
  const dbPath = sessionDbPath(profile);
  const bodyEmpty = JSON.stringify({ messages: [], session_id: sessionId, profile, count: 0 });
  if (!dbPath || !fs.existsSync(dbPath)) // fresh profile: empty is the honest answer, not a 404
    return done(res, send(res, 200, bodyEmpty), t0, ip, 'session_messages', 200, undefined, 0);
  let db;
  try {
    const { DatabaseSync } = await import('node:sqlite'); // lazy import — same 503 posture as /sessions
    db = new DatabaseSync(dbPath, { readOnly: true });
    // display_order is the gateway's stable transcript order; its earliest rows (and the odd
    // summary row) predate the column, so id is the fallback — both ascend monotonically.
    const rows = db.prepare(`
      SELECT role, content, timestamp, _compressed_summary AS summ
      FROM messages
      WHERE session_id = ? AND role IN ('user', 'assistant') AND active = 1
        AND COALESCE(display_kind, '') NOT IN ('hidden', 'process_complete', 'internal_notification')
        AND ((content IS NOT NULL AND content <> '') OR _compressed_summary = '1')
      ORDER BY COALESCE(display_order, id) DESC
      LIMIT ?
    `).all(sessionId, limit);
    rows.reverse(); // newest-window → oldest-first reading order
    const messages = rows.map((r) => {
      const text = String(r.content ?? '').trim();
      return {
        role: String(r.summ) === '1' ? 'summary' : String(r.role),
        content: text.length > MSGS_CONTENT_MAX ? text.slice(0, MSGS_CONTENT_MAX) + '…' : text,
        ts: saneEpochSec(r.timestamp),
      };
    }).filter((m) => m.content.length > 0);
    const body = JSON.stringify({ messages, session_id: sessionId, profile, count: messages.length });
    return done(res, send(res, 200, body), t0, ip, 'session_messages', 200, undefined, body.length);
  } catch (e) {
    return done(res, sendErr(res, 503, `messages store unavailable: ${String(e?.message || e)}`.slice(0, 200)), t0, ip, 'session_messages', 503);
  } finally {
    try { db?.close(); } catch { /* already gone */ }
  }
}

/** Log one structured line per request; `res` kept as first arg so call sites read uniformly. */
function done(_res, _void, t0, ip, ep, status, cache, bytes) {
  logLine({ ev: 'req', ip, ep, status, ms: Date.now() - t0, ...(cache ? { cache } : {}), ...(bytes ? { bytes } : {}) });
}

// ── server ─────────────────────────────────────────────────────────────────
const server = http.createServer(async (req, res) => {
  const ip = (req.socket.remoteAddress || '').replace('::ffff:', '');
  const url = (req.url || '/').split('?')[0];
  try {
    if (req.method === 'GET' && url === '/health') {
      logLine({ ev: 'req', ip, ep: 'health', status: 200, ms: 0 });
      // BATCH-4 #9: the watch's Settings shows WHICH engine would answer a streamed ask.
      return send(res, 200, JSON.stringify({
        status: 'ok', version: VERSION, uptime_s: Math.round((Date.now() - START_MS) / 1000),
        stt: sttEngineStatus(cfg, VENV_PY, WK_DIR),
      }));
    }
    const auth = req.headers.authorization || '';
    if (!auth.startsWith('Bearer ') || !timingSafeEqual(auth.slice(7), TOKEN)) {
      return done(res, sendErr(res, 401, 'unauthorized'), Date.now(), ip, url.slice(1), 401);
    }
    // ── body tools (W1-W8): snapshot ingest + latest + per-tool answers ──────
    if (req.method === 'POST' && url === '/health/snapshot') {
      const t0 = Date.now();
      let body;
      try { body = JSON.parse((await readBody(req, SNAPSHOT_MAX_BYTES)).toString('utf8')); }
      catch (e) { return done(res, sendErr(res, e.status || 400, e.status ? `body >${SNAPSHOT_MAX_BYTES / 1024}KB` : 'invalid JSON body'), t0, ip, 'snapshot', e.status || 400); }
      if (!body || typeof body !== 'object' || Array.isArray(body))
        return done(res, sendErr(res, 400, 'snapshot must be a JSON object'), t0, ip, 'snapshot', 400);
      const stored = { ...body, received_at: new Date().toISOString(), from: ip };
      await writeJsonAtomic(SNAPSHOT_PATH, stored);
      return done(res, send(res, 200, JSON.stringify({ ok: true, stored_ts: stored.received_at })),
        t0, ip, 'snapshot', 200, undefined, JSON.stringify(stored).length);
    }
    if (req.method === 'GET' && url === '/health/latest') {
      const snap = readSnapshot();
      if (!snap) return done(res, sendErr(res, 404, 'no snapshot yet'), Date.now(), ip, 'latest', 404);
      return done(res, send(res, 200, JSON.stringify(snap)), Date.now(), ip, 'latest', 200);
    }
    const mTool = /^\/health\/tool\/([a-z0-9_]+)$/.exec(url);
    if (req.method === 'GET' && mTool) {
      if (!TOOL_NAMES.includes(mTool[1])) return done(res, sendErr(res, 404, 'unknown tool'), Date.now(), ip, mTool[1], 404);
      const result = toolResult(mTool[1], readSnapshot());
      return done(res, send(res, 200, JSON.stringify(result)), Date.now(), ip, mTool[1], 200);
    }
    // Agent switcher (watch bottom bar): gateway profiles. Bearer-auth like everything else.
    if (req.method === 'GET' && url === '/agents') {
      const t0 = Date.now();
      const agents = listAgents();
      return done(res, send(res, 200, JSON.stringify({ agents })), t0, ip, 'agents', 200, undefined, agents.length);
    }
    // Cross-profile sessions for the watch's Sessions screen (owner: continue computer chats).
    // Session management for the watch's long-press menu (SPEC §12.4 follow-up): fork any session
    // into the current agent, delete any session. Bearer-guarded like every other route.
    if (req.method === 'POST' && (url === '/session_fork' || url === '/session_delete' || url === '/session_archive')) {
      let q;
      try { q = JSON.parse((await readBody(req, JSON_MAX_BYTES)).toString('utf8')); }
      catch { return done(res, sendErr(res, 400, 'bad json body'), Date.now(), ip, url.slice(1), 400); }
      if (url === '/session_fork') return await handleSessionFork(req, res, ip, q);
      if (url === '/session_archive') return await handleSessionArchive(req, res, ip, q);
      return await handleSessionDelete(req, res, ip, q);
    }
    // Full-text session search for the watch's Sessions screen (SPEC §12.4).
    if (req.method === 'GET' && url === '/search') {
      const q = Object.fromEntries(new URL(req.url, 'http://watchkit').searchParams);
      return await handleSearch(req, res, ip, q);
    }
    // Per-profile gateway keys so the watch can sign requests for ANY agent (SPEC §12.2).
    if (req.method === 'GET' && url === '/agent_keys') return await handleAgentKeys(req, res, ip);
    // Slash-command catalog for the watch's ⌘ overlay (SPEC §12.3). Bearer like every endpoint.
    if (req.method === 'GET' && url === '/catalog') {
      const q = Object.fromEntries(new URL(req.url, 'http://watchkit').searchParams);
      return await handleCatalog(req, res, ip, q);
    }
    if (req.method === 'GET' && url === '/approvals_waiting') return handleApprovalsWaiting(req, res, ip);
    if (req.method === 'GET' && url === '/sessions') {
      const q = Object.fromEntries(new URL(req.url, 'http://watchkit').searchParams);
      return await handleSessions(req, res, ip, q);
    }
    // Transcript backfill for adopted sessions (owner: computer sessions opened empty on adopt).
    if (req.method === 'GET' && url === '/session_messages') {
      const q = Object.fromEntries(new URL(req.url, 'http://watchkit').searchParams);
      return await handleSessionMessages(req, res, ip, q);
    }
    if (req.method === 'GET' && url === '/img') {
      const q = Object.fromEntries(new URL(req.url, 'http://watchkit').searchParams);
      return await handleImg(req, res, ip, q);
    }
    if (req.method === 'GET' && url === '/media') {
      const q = Object.fromEntries(new URL(req.url, 'http://watchkit').searchParams);
      return await handleMedia(req, res, ip, q);
    }
    if (req.method === 'POST' && url === '/img') {
      let body;
      try { body = JSON.parse((await readBody(req, JSON_MAX_BYTES)).toString('utf8')); }
      catch (e) { return done(res, sendErr(res, e.status || 400, e.status ? 'body >1MB' : 'invalid JSON body'), Date.now(), ip, 'img', e.status || 400); }
      return await handleImg(req, res, ip, body && typeof body === 'object' ? body : {});
    }
    if (req.method === 'POST' && url === '/render/mermaid') return await handleRender(req, res, ip);
    if (req.method === 'POST' && url === '/stt/whisper') return await handleStt(req, res, ip);
    return done(res, sendErr(res, 404, 'not found'), Date.now(), ip, url.slice(1), 404);
  } catch (e) {
    logLine({ ev: 'req', ip, ep: url, status: 500, ms: 0, err: String(e?.message || e) });
    if (!res.headersSent) sendErr(res, 500, 'internal');
  }
});

// ── MCP stdio server (SPEC W8: `node watchkit.mjs mcp` for the Hermes wear profile) ──
// Newline-delimited JSON-RPC 2.0 over stdin/stdout. Zero deps. Reads the SAME snapshot store
// the watch writes (via loopback HTTP so the daemon stays the single writer of truth).
// env: WATCHKIT_URL (default http://127.0.0.1:<port>), WATCHKIT_TOKEN (falls back to config).
// MCP_MODE itself is defined with the config (logLine needs it early).
const MCP_TOOLS = [
  { name: 'watch_heart_rate', description: 'Latest heart rate (bpm) from the owner\'s Galaxy Watch, pushed within the last 2 hours. Read-only body data; only available if the owner enabled the Heart rate tool.', inputSchema: { type: 'object', properties: {} } },
  { name: 'watch_steps', description: 'Steps today (daily total) from the owner\'s Galaxy Watch snapshot, pushed within the last 2 hours.', inputSchema: { type: 'object', properties: {} } },
  { name: 'watch_sleep', description: 'Last sleep session (start/end epoch ms + duration_hours) from the owner\'s Galaxy Watch snapshot, pushed within the last 2 hours.', inputSchema: { type: 'object', properties: {} } },
  { name: 'watch_spo2', description: 'Latest blood-oxygen saturation (%SpO2) from the owner\'s Galaxy Watch snapshot, pushed within the last 2 hours.', inputSchema: { type: 'object', properties: {} } },
  { name: 'watch_location', description: 'Where the owner\'s Galaxy Watch is right now: lat/lng + accuracy_m + age_s from the watch\'s last location fix (fresh within 30 minutes; only if the owner enabled the Location tool). Answer "where is my watch" style questions with this.', inputSchema: { type: 'object', properties: {} } },
];

async function fetchToolResult(name) {
  const base = (process.env.WATCHKIT_URL || `http://127.0.0.1:${PORT}`).replace(/\/+$/, '');
  try {
    const r = await fetch(`${base}/health/tool/${encodeURIComponent(name)}`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
      signal: AbortSignal.timeout(8000),
    });
    const text = await r.text();
    try { return JSON.parse(text); }
    catch { return { tool: name, ok: false, reason: `watchkit http ${r.status}: ${text.slice(0, 120)}` }; }
  } catch (e) {
    return { tool: name, ok: false, reason: `watchkit unreachable at ${base}: ${e.message}` };
  }
}

const mcpSend = (obj) => process.stdout.write(JSON.stringify(obj) + '\n');
const mcpResult = (id, result) => mcpSend({ jsonrpc: '2.0', id, result });
const mcpError = (id, code, message) => mcpSend({ jsonrpc: '2.0', id, error: { code, message } });

async function mcpHandle(msg) {
  if (!msg || typeof msg !== 'object') return mcpError(null, -32600, 'invalid request');
  const { id, method, params } = msg;
  const isNotification = id === undefined || id === null; // JSON-RPC notifications get no response
  switch (method) {
    case 'initialize':
      return mcpResult(id, {
        protocolVersion: (params && params.protocolVersion) || '2024-11-05',
        capabilities: { tools: {} },
        serverInfo: { name: 'hermes-watchkit', version: VERSION },
      });
    case 'ping':
      return mcpResult(id, {});
    case 'tools/list':
      return mcpResult(id, { tools: MCP_TOOLS });
    case 'tools/call': {
      const name = params && params.name;
      if (!TOOL_NAMES.includes(name)) {
        return mcpResult(id, {
          content: [{ type: 'text', text: JSON.stringify({ tool: name ?? null, ok: false, reason: 'unknown tool' }) }],
          isError: true,
        });
      }
      const result = await fetchToolResult(name);
      return mcpResult(id, { content: [{ type: 'text', text: JSON.stringify(result) }], isError: result.ok !== true });
    }
    default:
      if (isNotification) return; // e.g. notifications/initialized — acknowledge by silence
      return mcpError(id, -32601, `method not found: ${method}`);
  }
}

function runMcp() {
  logLine({ ev: 'mcp-start', version: VERSION, pid: process.pid });
  let buf = '';
  let stdinEnded = false;
  let pending = 0;
  const maybeExit = () => { if (stdinEnded && pending === 0) process.exit(0); };
  const handle = (msg) => {
    pending++;
    Promise.resolve(mcpHandle(msg)).catch((e) => mcpError(msg?.id ?? null, -32603, String(e?.message || e)))
      .finally(() => { pending--; setImmediate(maybeExit); });
  };
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    buf += chunk;
    let nl;
    while ((nl = buf.indexOf('\n')) !== -1) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (!line) continue;
      let msg;
      try { msg = JSON.parse(line); } catch { mcpError(null, -32700, 'parse error'); continue; }
      if (Array.isArray(msg)) msg.forEach((m) => handle(m));
      else handle(msg);
    }
  });
  process.stdin.on('end', () => { stdinEnded = true; maybeExit(); }); // supervisor closed the pipe
}

if (MCP_MODE) {
  runMcp();
} else {
  // BATCH-4 #9: streamed mic sessions ride the same socket authority as every endpoint.
  attachSttStream(server, {
    cfg, venvPy: VENV_PY, wkDir: WK_DIR, TOKEN, CHILD_ENV, logLine,
    timingSafe: (a, b) => timingSafeEqual(a, b),
  });
  server.listen(PORT, HOST, () => {
    logLine({ ev: 'start', version: VERSION, host: HOST, port: PORT, pid: process.pid });
    // AUTO-KEY NEW AGENTS without waiting for any watch poll (owner 2026-11-02): own timer, same
    // throttled sweep the away-poll drives. unref() so it never keeps the process alive on its own.
    setInterval(() => { try { seedProfileKeys(false); } catch { /* next round */ } }, 60_000).unref();
  });
  for (const s of ['SIGTERM', 'SIGINT']) process.on(s, () => { logLine({ ev: 'stop', sig: s }); server.close(() => process.exit(0)); });
}
