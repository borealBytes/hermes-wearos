"""WearKit Gateway provider — maths and routing, stdlib-only (plugin rule 10)."""

import io
import math
import struct
import wave

import tts_provider


def _sine_wav(seconds=1.0, rate=24000, freq=100.0, amp=12000):
    n = int(seconds * rate)
    frames = b"".join(struct.pack("<h", int(amp * math.sin(2 * math.pi * freq * i / rate)))
                      for i in range(n))
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setparams((1, 2, rate, 0, "NONE", "not compressed"))
        w.writeframes(frames)
    return buf.getvalue()


def test_stretch_shrinks_duration_keeps_header():
    wav = _sine_wav(seconds=2.0)
    out = tts_provider._stretch(wav, 1.5)
    with wave.open(io.BytesIO(out)) as w:
        assert w.getframerate() == 24000           # header untouched
        assert abs(w.getnframes() - 2 * 24000 / 1.5) < 2400  # ~1/1.5 the length
    with wave.open(io.BytesIO(wav)) as w:
        assert abs(w.getnframes() - 2 * 24000) < 10


def test_stretch_rejects_garbage():
    try:
        tts_provider._stretch(b"not-a-wav-at-all", 1.2)
        assert False, "wave header must be validated, not shipped broken"
    except wave.Error:
        pass


def test_provider_identity():
    p = tts_provider.WearKitGatewayProvider()
    assert p.name == "wearkit-gateway"
    assert p.display_name == "WearKit Gateway"
    assert "wearkit-gateway" not in {
        "edge", "elevenlabs", "openai", "minimax", "xai", "mistral",
        "gemini", "neutts", "kittentts", "piper", "deepinfra"}  # registry collision rule


def _no_cache(monkeypatch):
    import voiceout
    monkeypatch.setattr(voiceout, "_cache_get", lambda *a, **k: None)
    monkeypatch.setattr(voiceout, "_cache_put", lambda *a, **k: None)


def test_synthesize_routes_through_voiceout(tmp_path, monkeypatch):
    import voiceout
    _no_cache(monkeypatch)
    monkeypatch.setitem(voiceout._SYNTH, "air",
                        lambda text, card, speed: (_sine_wav(0.5), 24000))
    monkeypatch.setattr(voiceout, "read_prefs",
                        lambda: {"rev": 1, "cards": {}, "global": {"speed": 1.0},
                                 "modes": {}})
    p = tts_provider.WearKitGatewayProvider()
    out = p.synthesize("hello", str(tmp_path / "x.mp3"), speed=None)
    assert out.endswith(".wav")
    with wave.open(out) as w:
        assert abs(w.getnframes() - 0.5 * 24000) < 200   # natural 1x: dial 1.0 = no stretch


def test_synthesize_applies_canonical_dial(tmp_path, monkeypatch):
    import voiceout
    _no_cache(monkeypatch)
    monkeypatch.setitem(voiceout._SYNTH, "air",
                        lambda text, card, speed: (_sine_wav(1.0), 24000))
    monkeypatch.setattr(voiceout, "read_prefs",
                        lambda: {"rev": 1, "cards": {}, "global": {"speed": 1.25},
                                 "modes": {}})
    p = tts_provider.WearKitGatewayProvider()
    out = p.synthesize("hello", str(tmp_path / "y.mp3"), speed=None)
    with wave.open(out) as w:
        assert abs(w.getnframes() - 24000 / 1.25) < 600  # dial respected, compressed


def test_synthesize_raises_honestly_when_engine_absent(tmp_path, monkeypatch):
    import voiceout
    saved = dict(voiceout._SYNTH)
    voiceout._SYNTH.clear()
    try:
        p = tts_provider.WearKitGatewayProvider()
        try:
            p.synthesize("hi", str(tmp_path / "z.wav"))
            assert False, "must raise, never write silence"
        except RuntimeError as exc:
            assert "wearkit-gateway" in str(exc)
    finally:
        voiceout._SYNTH.update(saved)
