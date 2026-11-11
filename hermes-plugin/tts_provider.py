"""WearKit Gateway — the plugin TTS provider for the Settings ▸ Voice dropdown.

Owner ruling 2026-11-10: the desktop picker should offer ONE entry that routes all
Hermes speech through the Wear Kit's engine — no second model, no second queue.
Core already merges plugin-registered providers into the settings schema
(``hermes_cli/web_server_config.py`` reads ``agent.tts_registry``), so registering
here is all it takes for ``wearkit-gateway`` to appear next to edge/neutts/piper.

One engine to obey the ruling: ``synthesize`` goes through ``voiceout.synthesize``
— the same serve worker, FIFO ticket gate, cache, and silence guard the watch
voice-out uses. The voice is the card bound on the Wear Kit pane (voice=None
resolves the agent binding; a ``voice`` value from settings addresses a card id).
Pacing: the returned clip is natural 1x (pacing-at-playback law); a file handed
to a plain player gets an honest stdlib pitch-preserving time-stretch by the
canonical dial so desktop previews match the watch the dial describes.
"""

from __future__ import annotations

import io
import logging
import wave
from typing import Any, Optional

logger = logging.getLogger(__name__)
def _voiceout():
    """The plugin's canonical prefs module, either import shape (see __init__ pattern)."""
    try:
        from . import voiceout          # type: ignore
    except ImportError:
        import voiceout                 # type: ignore
    return voiceout


try:
    from agent.tts_provider import TTSProvider
except ModuleNotFoundError:  # standalone (plugin tests, no host on path)
    class TTSProvider:  # type: ignore[no-redef]
        streams_pcm = False
        stream_sample_rate = None


def _pcm_of(wav: bytes) -> tuple[bytes, int]:
    """Raw int16 mono frames + rate from a WAV (the streaming wire format)."""
    with wave.open(io.BytesIO(wav)) as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2:
            raise RuntimeError("wearkit-gateway: expected 16-bit mono engine wav")
        return w.readframes(w.getnframes()), w.getframerate()


def _pcm_stretch(frames: bytes, speed: float) -> bytes:
    """Time-compress raw int16 mono by `speed` (linear interpolation; audioop is
    gone in 3.13+ and stdlib has no WSOLA — see _stretch for the honest rationale)."""
    samples = [int.from_bytes(frames[i:i + 2], "little", signed=True)
               for i in range(0, len(frames) - len(frames) % 2, 2)]
    out = bytearray()
    pos, n = 0.0, len(samples)
    while pos < n - 1:
        i = int(pos); frac = pos - i
        v = int(samples[i] * (1 - frac) + samples[i + 1] * frac)
        out += v.to_bytes(2, "little", signed=True)
        pos += speed
    return bytes(out)


def _stretch(wav: bytes, speed: float) -> bytes:
    """Time-compress by `speed` (linear-interpolated), header UNCHANGED — so the
    clip plays `speed`× shorter with pitch up `speed`× (turntable law; audioop is
    gone in 3.13+ and stdlib has no WSOLA). Deliberate: the pane dial is small
    (0.5–2×) and a preview-grade stretch beats a silent lie; the real watch path
    is pitch-preserved ExoPlayer playback (pacing-at-playback, §19.4(g)) and
    never runs through here."""
    with wave.open(io.BytesIO(wav)) as w:
        rate, width, chans = w.getframerate(), w.getsampwidth(), w.getnchannels()
        frames = w.readframes(w.getnframes())
    if width != 2:
        return wav  # we only ever emit 16-bit; anything else ships untouched
    out = _pcm_stretch(frames, speed)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setparams((chans, width, rate, 0, "NONE", "not compressed"))
        w.writeframes(out)
    return buf.getvalue()


class WearKitGatewayProvider(TTSProvider):
    """'WearKit Gateway': all Hermes TTS rides the Wear Kit's NeuTTS Air worker.

    STREAMS (owner ruling 2026-11-10): the desktop speaker owns the sentence
    splitter (SentenceChunker in tools/tts_streaming.py) — declaring streams_pcm
    means a live reply starts playing at SENTENCE ONE while later sentences are
    still rendering, instead of the whole paragraph synthesizing first. Each
    stream() call still guards itself through voiceout's per-sentence cache."""

    streams_pcm = True
    stream_sample_rate = 24000        # the engine's true rate (measured header);
                                      # a drift raises rather than plays garbled

    @property
    def name(self) -> str:
        return "wearkit-gateway"

    @property
    def display_name(self) -> str:
        return "WearKit Gateway"

    def is_available(self) -> bool:
        try:
            voiceout = _voiceout()
            return bool(voiceout._SYNTH)
        except Exception:
            return False

    def list_voices(self) -> list[dict[str, Any]]:
        try:
            voiceout = _voiceout()
            cards = (voiceout.read_prefs().get("cards") or {})
        except Exception:
            return []
        return [{"id": cid, "display": str(m.get("label") or cid)}
                for cid, m in cards.items()]

    def default_voice(self) -> Optional[str]:
        voices = self.list_voices()
        return voices[0]["id"] if voices else None

    def _resolve_voice(self, voice: Optional[str]) -> Optional[str]:
        """Voice law: explicit settings voice > this agent's pane binding > global
        binding > the single configured card. The Voice-out card's dropdown IS
        'the currently selected voice'; dictation must not refuse just because no
        one ever wrote a 'desktop' binding."""
        if voice:
            return voice
        voiceout = _voiceout()
        prefs = voiceout.read_prefs()
        vm = prefs.get("voices") or {}
        cards = prefs.get("cards") or {}
        return (vm.get("desktop") or vm.get("__default__")
                or (next(iter(cards)) if len(cards) == 1 else "")) or None

    def synthesize(self, text: str, output_path: str, *,
                   voice: Optional[str] = None, model: Optional[str] = None,
                   speed: Optional[float] = None, format: str = "mp3",
                   **extra: Any) -> str:
        voiceout = _voiceout()
        voice = self._resolve_voice(voice)
        r = voiceout.synthesize(text, "desktop", want_words=False, card_id=voice)
        if not r or not r.get("ok") or not r.get("audio"):
            raise RuntimeError("wearkit-gateway: " + str(
                (r or {}).get("error") or "no audio"))
        audio = r["audio"]
        # respect the caller's speed, else the canonical dial (voiceout.yaml global)
        eff = float(speed) if speed else \
            float((voiceout.read_prefs().get("global") or {}).get("speed") or 1.0)
        if eff and abs(eff - 1.0) > 0.001:
            audio = _stretch(audio, eff)
        out = output_path.rsplit(".", 1)[0] + ".wav"  # wav is the truthful extension
        with open(out, "wb") as f:
            f.write(audio)
        return out

    def stream(self, text: str, *, voice: Optional[str] = None,
               model: Optional[str] = None, speed: Optional[float] = None,
               format: str = "pcm", **extra: Any):
        """Yield int16 mono PCM at stream_sample_rate, one chunk per sentence."""
        voiceout = _voiceout()
        voice = self._resolve_voice(voice)
        eff = float(speed) if speed else \
            float((voiceout.read_prefs().get("global") or {}).get("speed") or 1.0)
        for sent in voiceout._sentences(text, mode=voiceout.chunk_mode()):
            r = voiceout.synthesize(sent, "desktop", want_words=False,
                                    card_id=voice)
            if not r or not r.get("ok") or not r.get("audio"):
                raise RuntimeError("wearkit-gateway: " + str(
                    (r or {}).get("error") or "no audio"))
            frames, rate = _pcm_of(r["audio"])
            if rate != self.stream_sample_rate:
                raise RuntimeError(
                    "wearkit-gateway: engine rate drift %d != declared %d"
                    % (rate, self.stream_sample_rate))
            yield _pcm_stretch(frames, eff) if eff and abs(eff - 1.0) > 0.001 \
                else frames


def register(ctx) -> None:
    """Called from the plugin's register(): adds the dropdown entry + routes."""
    ctx.register_tts_provider(WearKitGatewayProvider())
