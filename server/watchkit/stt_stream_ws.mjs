/**
 * stt_stream_ws.mjs — BATCH-4 #9: the watch streams raw mic PCM to the gateway.
 *
 *   WS /stt/stream (bearer, same token as every watchkit endpoint — header or ?token=)
 *     client → server : BINARY frames = mic audio, format SNIFFED from the first bytes:
 *                        'OggS…'  → Ogg/Opus (current watch: 24 kbit/s, the best speech codec)
 *                        ADTS     → AAC-LC 32k (watch without an Opus encoder)
 *                        else     → raw 16 kHz mono PCM16-LE (legacy watches)
 *                       any format is transcoded to the engine's PCM here — engines never change.
 *                       TEXT   frame  = {"end":true} → wearer stopped talking
 *     server → client : TEXT frames, JSON lines from the engine adapter:
 *                       {"partial":"…"} (engine-dependent), {"final":"…"}, {"error":"…"}
 *
 * Engine: config.json `stt_stream_engine` picks the DEFAULT ("whisper" buffered — final only,
 * CPU-friendly | "parakeet" — transcribe.cpp GGUF streaming, partials WHILE talking). The watch
 * may override PER SESSION with `?engine=<id>` (Settings ▸ Voice in ▸ Gateway picker lists the
 * engines /health reports, owner 2026-10-30: "it should let you … select … installed models").
 * Unknown engine ids fall back to the configured default, never an error mid-sentence.
 *
 * /health shape: stt:{engine, ready, missing, engines:[{engine,ready,streaming,missing},…]}.
 * The engines[] list comes from `stt_stream.py --check`, run CACHED in the background (a probe
 * loads a 731 MB model — the failover probe must not pay that per hit).
 *
 * Zero framework: the RFC6455 subset here is exactly what the watch (okhttp WebSocket) and
 * curl/wscat probes send — masked text/binary + close. Keep it that small.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const HARD_CAP_MS = 120_000; // one streamed utterance may never outlive this

export const STT_ENGINES = ['whisper', 'parakeet']; // mirrors stt_stream.py --engine choices

// ── engine inventory (owner: the picker must list what is INSTALLED, honestly) ──────────────
// `stt_stream.py --check` prints one JSON line per engine; it loads the parakeet GGUF (~1–2 s,
// 731 MB), so /health serves the CACHE and this refreshes it in the background at most every
// STT_PROBE_TTL_MS. First health beat may show engines:[] — the watch handles that by falling
// back to the single-engine label (pre-selection behavior), never a wrong answer.
//
// v2 (D16): the engine may live in ANOTHER python — the Wear Kit pane installs a pinned,
// checksum-verified Parakeet into the plugin's OWN data dir (private venv + built .so + model).
// Candidates therefore come from the caller: [{python, env}] in preference order; each is
// probed with the same --check and results merge per ENGINE (a ready row wins). Sessions then
// run the engine on the python that reported it ready — whisper stays on watchkit's venv, a
// plugin-installed parakeet runs from the plugin venv with PARAKEET_GGUF/TRANSCRIBE_LIBRARY
// pointed at its files. No candidate → the old single-venv behavior verbatim.
const STT_PROBE_TTL_MS = 120_000;
let sttEngines = null;
let sttEnginesAt = 0;
let sttProbeLive = false;
const sttEngineRun = new Map(); // engine -> {python, env} that reported it ready

function sttProbe(probes) {
  if (sttProbeLive) return;
  sttProbeLive = true;
  const queue = probes.filter((p) => p && fs.existsSync(p.python));
  const rows = [];
  const finish = () => {
    sttProbeLive = false;
    sttEnginesAt = Date.now();
    if (!rows.length) return; // broken probe: keep the old list, never blank the picker
    const merged = new Map();
    for (const r of rows) {
      const prev = merged.get(r.engine);
      if (!prev || (!prev.ready && r.ready)) merged.set(r.engine, r);
    }
    sttEngines = [...merged.values()];
  };
  const runNext = () => {
    const p = queue.shift();
    if (!p) { finish(); return; }
    const child = spawn(p.python, [p.script, '--check'],
      { cwd: p.cwd, env: p.env ? { ...process.env, ...p.env } : process.env,
        stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '', timer = setTimeout(() => { try { child.kill('SIGKILL'); } catch { } }, 30_000);
    child.stdout.on('data', (d) => { out += d.toString('utf8'); });
    child.on('exit', () => {
      clearTimeout(timer);
      try {
        for (const l of out.trim().split('\n').filter(Boolean)) {
          const row = JSON.parse(l);
          if (row && row.engine) {
            rows.push(row);
            if (row.ready) sttEngineRun.set(row.engine, { python: p.python, env: p.env || null });
          }
        }
      } catch { /* malformed probe output: this candidate contributes nothing */ }
      runNext();
    });
    child.on('error', () => { clearTimeout(timer); runNext(); });
  };
  runNext();
}

export function sttEngineStatus(cfg, venvPy, wkDir, extraProbes) {
  const engine = cfg.stt_stream_engine || 'whisper';
  const probes = [{ python: venvPy, script: path.join(wkDir, 'stt_stream.py'), cwd: wkDir },
    ...(extraProbes || [])];
  const usable = probes.filter((p) => p.python && fs.existsSync(p.python) && fs.existsSync(p.script));
  if (usable.length && Date.now() - sttEnginesAt > STT_PROBE_TTL_MS) sttProbe(usable);
  const ready = usable.length > 0;
  return {
    engine, ready,
    missing: ready ? null : 'run scripts/whisper-env.sh',
    engines: sttEngines || [], // [] until the first probe lands — honest "don't know yet"
  };
}

/** The {python, env} that reported ENGINE ready (fall back to the primary venv, exactly
 *  the pre-D16 behavior for engines watchkit itself hosts). */
export function sttEngineRunner(engine, fallbackPython, fallbackScript, wkDir) {
  const hit = sttEngineRun.get(engine);
  if (hit) return { python: hit.python, env: hit.env, script: fallbackScript };
  return { python: fallbackPython, env: null, script: fallbackScript };
}

export function attachSttStream(server, ctx) {
  server.on('upgrade', (req, socket, head) => {
    const url = (req.url || '/').split('?')[0];
    if (url !== '/stt/stream') { socket.destroy(); return; }
    const q = new URLSearchParams((req.url || '').split('?')[1] || '');
    const auth = req.headers.authorization || '';
    const token = auth.startsWith('Bearer ') ? auth.slice(7) : q.get('token') || '';
    const tOk = ctx.timingSafe(token, ctx.TOKEN);
    const key = req.headers['sec-websocket-key'];
    if (!tOk || !key || req.headers.upgrade?.toLowerCase() !== 'websocket') {
      socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }
    const accept = crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\nConnection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
    );
    // Per-session engine pick (watch Settings ▸ Voice in ▸ Gateway). Unknown ids are NOT an
    // error — they fold to the configured default so an old watch or a typo still transcribes.
    const want = (q.get('engine') || '').trim().toLowerCase();
    const engine = STT_ENGINES.includes(want) ? want : null;
    pipeSession(socket, head, ctx, (req.socket.remoteAddress || '').replace('::ffff:', ''), engine);
  });
}

/* ── RFC6455 subset ───────────────────────────────────────────────────────── */

function encodeFrame(text) {
  const payload = Buffer.from(text, 'utf8');
  const len = payload.length;
  let header;
  if (len < 126) { header = Buffer.from([0x81, len]); }
  else if (len < 65536) { header = Buffer.alloc(4); header[0] = 0x81; header[1] = 126; header.writeUInt16BE(len, 2); }
  else { header = Buffer.alloc(10); header[0] = 0x81; header[1] = 127; header.writeBigUInt64BE(BigInt(len), 2); }
  return Buffer.concat([header, payload]);
}

/**
 * Pull complete masked client frames out of a growing buffer.
 * Returns {frames:[{op,payload}], rest:Buffer}. Continuations concatenate.
 */
export function decodeFrames(buf, acc) {
  const frames = [];
  let o = 0;
  while (o + 2 <= buf.length) {
    const b0 = buf[o], b1 = buf[o + 1];
    const fin = (b0 & 0x80) !== 0;
    const op = b0 & 0x0f;
    if (!(b1 & 0x80)) return { frames: [], rest: buf }; // unmasked client frame — illegal, give up
    let len = b1 & 0x7f, hlen = 2;
    if (len === 126) { if (buf.length < o + 4) break; len = buf.readUInt16BE(o + 2); hlen = 4; }
    else if (len === 127) { if (buf.length < o + 10) break; len = Number(buf.readBigUInt64BE(o + 2)); hlen = 10; }
    if (buf.length < o + hlen + 4 + len) break;
    const mask = buf.subarray(o + hlen, o + hlen + 4);
    const payload = Buffer.alloc(len);
    for (let i = 0; i < len; i++) payload[i] = buf[o + hlen + 4 + i] ^ mask[i & 3];
    o += hlen + 4 + len;
    if (op === 0x0 || op === 0x1 || op === 0x2) {
      acc.chunks.push(payload);
      if (fin) {
        frames.push({ op: acc.op ?? op, payload: Buffer.concat(acc.chunks) });
        acc.chunks = []; acc.op = null;
      } else if (acc.op === null) acc.op = op;
    } else if (op === 0x8) {
      frames.push({ op: 0x8, payload: Buffer.alloc(0) });
    }
  }
  return { frames, rest: buf.subarray(o) };
}

/* ── session plumbing ─────────────────────────────────────────────────────── */

/**
 * First-bytes format sniff (owner 2026-11-02: stop shipping the raw wave): 'OggS' → Ogg/Opus;
 * a header that is exactly our ADTS shape (MPEG-2 sync, AAC-LC profile, the 16 kHz table index,
 * mono, sane frame length) → AAC; anything else → legacy raw PCM. The PCM-vs-ADTS ambiguity
 * (near-silence PCM can start 0xFF 0xF0) is broken by requiring sf=16k AND LC AND mono AND a
 * plausible length — a PCM byte pair matching ALL FOUR is rare, and the worst case is one
 * garbled first frame, not a lost utterance.
 */
export function sniffWire(p) {
  if (p.length >= 4 && p[0] === 0x4f && p[1] === 0x67 && p[2] === 0x67 && p[3] === 0x53) return 'ogg';
  if (p.length >= 7 && p[0] === 0xff && (p[1] & 0xf0) === 0xf0) {
    const profile = (p[2] >> 6) & 3;
    const sf = (p[2] >> 2) & 0xf;
    const chan = ((p[2] & 1) << 2) | ((p[3] >> 6) & 3);
    const len = ((p[3] & 0xf) << 11) | (p[4] << 3) | ((p[5] >> 5) & 7);
    if (profile === 1 && sf === 8 && chan === 1 && len >= 8 && len <= 4000) return 'aac';
  }
  return 'pcm';
}

function pipeSession(socket, head, ctx, ip, engineOverride) {
  const status = sttEngineStatus(ctx.cfg, ctx.venvPy, ctx.wkDir, ctx.sttProbes);
  const engine = engineOverride || status.engine;
  const t0 = Date.now();
  ctx.logLine({ ev: 'stt-stream', ip, ep: '/stt/stream', engine, status: 101, ms: 0 });

  const sendText = (s) => { try { socket.write(encodeFrame(s)); } catch { /* gone */ } };
  let child = null, decoder = null, wire = null, stdinOpen = false, ended = false, closed = false, bytesIn = 0, sawFinal = false;

  const cleanup = (why) => {
    if (closed) return;
    closed = true;
    if (decoder) { try { decoder.kill('SIGKILL'); } catch { } }
    if (child) { try { child.kill('SIGTERM'); } catch { } }
    ctx.logLine({ ev: 'stt-stream', ip, ep: '/stt/stream', status: 0, ms: Date.now() - t0, bytes: bytesIn, why });
    // RFC6455 close frame (code 1000) BEFORE the TCP end — okhttp reports clean closes, and
    // an unframed hangup would look like a protocol error in the watch logs.
    try { socket.write(Buffer.from([0x88, 0x02, 0x03, 0xe8])); } catch { }
    try { socket.end(); } catch { }
  };
  const cap = setTimeout(() => { if (!sawFinal) sendText(JSON.stringify({ error: 'stt window exceeded (120s)' })); cleanup('cap'); }, HARD_CAP_MS);

  const startEngine = () => {
    if (!status.ready) { sendText(JSON.stringify({ error: status.missing })); cleanup('not-ready'); return; }
    // v2 (D16): run the chosen engine on the python that PROBED it ready — watchkit's venv
    // for its own installs, the Wear Kit plugin's private venv for a pane-installed Parakeet.
    const runner = sttEngineRunner(engine, ctx.venvPy, path.join(ctx.wkDir, 'stt_stream.py'), ctx.wkDir);
    child = spawn(runner.python, [runner.script, '--engine', engine],
      { cwd: ctx.wkDir, env: runner.env
          ? { ...ctx.CHILD_ENV, ...runner.env }
          : ctx.CHILD_ENV,
        stdio: ['pipe', 'pipe', 'pipe'] });
    stdinOpen = true;
    let outBuf = '', errTail = '';
    child.stdout.on('data', (d) => {
      outBuf += d.toString('utf8');
      let nl;
      while ((nl = outBuf.indexOf('\n')) >= 0) {
        const line = outBuf.slice(0, nl).trim(); outBuf = outBuf.slice(nl + 1);
        if (!line) continue;
        if (/"final"\s*:/.test(line)) sawFinal = true;
        sendText(line);
      }
    });
    child.stderr.on('data', (d) => { errTail = (errTail + d.toString('utf8')).slice(-2000); });
    // 'close', NOT 'exit': 'exit' can fire while the final JSONL line still sits in the stdout
    // pipe, and cleanup's socket.end() would cut it off (a latent race the fast-path probe
    // exposed). 'close' guarantees stdout has been delivered before we decide and hang up.
    child.on('close', (code) => {
      if (!closed && !sawFinal) {
        sendText(JSON.stringify({ error: `engine exit ${code}: ${(errTail || 'no stderr').slice(-200)}` }));
      }
      clearTimeout(cap); cleanup(`exit ${code}`);
    });
  };

  // Opus/AAC lanes ride through ffmpeg: container-demux → s16le 16 kHz mono into the engine's
  // stdin, so stt_stream.py never learns a new format. Missing/broken ffmpeg surfaces as an
  // error frame, which the watch treats as "not answered yet" → its local Vosk fallback replays
  // the whole utterance (that buffer is raw PCM from the mic, independent of this leg).
  const startDecoder = (fmt) => {
    const ff = ctx.ffmpeg || 'ffmpeg';
    try {
      decoder = spawn(ff, ['-hide_banner', '-loglevel', 'error', '-nostdin', '-f', fmt, '-i', 'pipe:0',
        '-map', '0:a:0', '-vn', '-f', 's16le', '-ac', '1', '-ar', '16000', 'pipe:1'],
      { cwd: ctx.wkDir, stdio: ['pipe', 'pipe', 'pipe'] });
    } catch { decoder = null; sendText(JSON.stringify({ error: `mic decode spawn failed (${fmt})` })); return; }
    let errTail = '';
    decoder.stdout.pipe(child.stdin);
    decoder.stderr.on('data', (d) => { errTail = (errTail + d.toString('utf8')).slice(-1000); });
    decoder.on('error', (e) => { if (!closed && !sawFinal) sendText(JSON.stringify({ error: `mic decode: ${e.message}` })); });
    decoder.on('exit', (code) => {
      if (code && !closed && !sawFinal) sendText(JSON.stringify({ error: `mic decode exit ${code}: ${errTail.slice(-160)}` }));
    });
  };

  let buf = head && head.length ? Buffer.from(head) : Buffer.alloc(0);
  const acc = { chunks: [], op: null };
  socket.on('data', (d) => {
    buf = Buffer.concat([buf, d]);
    const { frames, rest } = decodeFrames(buf, acc);
    buf = rest;
    for (const f of frames) {
      if (f.op === 0x8) { cleanup('client-close'); return; }
      if (f.op === 0x2) {
        if (!child) startEngine();
        if (!child) continue;
        if (wire === null) {
          wire = sniffWire(f.payload);
          ctx.logLine({ ev: 'stt-stream', ip, ep: '/stt/stream', wire, ms: 0 });
          if (wire !== 'pcm') startDecoder(wire);
        }
        if (!stdinOpen) continue;
        if (decoder) {
          if (!decoder.stdin.destroyed) { decoder.stdin.write(f.payload); bytesIn += f.payload.length; }
        } else if (!child.stdin.destroyed) {
          child.stdin.write(f.payload); bytesIn += f.payload.length;
        }
      } else if (f.op === 0x1) {
        const t = f.payload.toString('utf8');
        if (/^\s*\{.*"end"\s*:\s*true/.test(t) && child && stdinOpen) {
          if (decoder && !decoder.stdin.destroyed) decoder.stdin.end(); // flushes through; pipe ends the engine
          else child.stdin.end();
          stdinOpen = false; ended = true;
        }
      }
    }
  });
  socket.on('error', () => cleanup('sock-err'));
  socket.on('close', () => cleanup('sock-close'));
  // a session that opened but never streamed anything should not linger
  setTimeout(() => { if (!child && !closed) cleanup('idle'); }, 15_000).unref?.();
  void ended;
}
