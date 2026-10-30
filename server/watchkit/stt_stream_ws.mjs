/**
 * stt_stream_ws.mjs — BATCH-4 #9: the watch streams raw mic PCM to the gateway.
 *
 *   WS /stt/stream (bearer, same token as every watchkit endpoint — header or ?token=)
 *     client → server : BINARY frames = 16 kHz mono PCM16-LE, ~100 ms each
 *                       TEXT   frame  = {"end":true} → wearer stopped talking
 *     server → client : TEXT frames, JSON lines from the engine adapter:
 *                       {"partial":"…"} (engine-dependent), {"final":"…"}, {"error":"…"}
 *
 * Engine = config.json `stt_stream_engine`: "whisper" (default; buffered — final only,
 * CPU-friendly) | "parakeet" (NeMo Unified-EN-6B streaming — partials WHILE talking; needs
 * nemo in the watchkit venv, honestly errors out when it is not).
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

export function sttEngineStatus(cfg, venvPy, wkDir) {
  const engine = cfg.stt_stream_engine || 'whisper';
  const ready = fs.existsSync(venvPy) && fs.existsSync(path.join(wkDir, 'stt_stream.py'));
  return { engine, ready, missing: ready ? null : 'run scripts/whisper-env.sh' };
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
    pipeSession(socket, head, ctx, (req.socket.remoteAddress || '').replace('::ffff:', ''));
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

function pipeSession(socket, head, ctx, ip) {
  const status = sttEngineStatus(ctx.cfg, ctx.venvPy, ctx.wkDir);
  const t0 = Date.now();
  ctx.logLine({ ev: 'stt-stream', ip, ep: '/stt/stream', engine: status.engine, status: 101, ms: 0 });

  const sendText = (s) => { try { socket.write(encodeFrame(s)); } catch { /* gone */ } };
  let child = null, stdinOpen = false, ended = false, closed = false, bytesIn = 0, sawFinal = false;

  const cleanup = (why) => {
    if (closed) return;
    closed = true;
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
    child = spawn(ctx.venvPy, [path.join(ctx.wkDir, 'stt_stream.py'), '--engine', status.engine],
      { cwd: ctx.wkDir, env: ctx.CHILD_ENV, stdio: ['pipe', 'pipe', 'pipe'] });
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
    child.on('exit', (code) => {
      if (!closed && !sawFinal) {
        sendText(JSON.stringify({ error: `engine exit ${code}: ${(errTail || 'no stderr').slice(-200)}` }));
      }
      clearTimeout(cap); cleanup(`exit ${code}`);
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
        if (child && stdinOpen) { child.stdin.write(f.payload); bytesIn += f.payload.length; }
      } else if (f.op === 0x1) {
        const t = f.payload.toString('utf8');
        if (/^\s*\{.*"end"\s*:\s*true/.test(t) && child && stdinOpen) {
          child.stdin.end(); stdinOpen = false; ended = true;
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
