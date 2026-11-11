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


LONG3 = ("The wear kit speaks every long reply sentence by sentence now. "
         "Each sentence is its own short generation, streamed to the speaker "
         "as raw PCM so playback starts while the rest is still rendering. "
         "Nobody waits for a giant paragraph to finish before hearing a word.")


def test_provider_declares_pcm_streaming():
    p = tts_provider.WearKitGatewayProvider()
    assert p.streams_pcm is True
    assert p.stream_sample_rate == 24000


def _fake_engine(monkeypatch, texts, wav=None):
    import voiceout
    _no_cache(monkeypatch)
    seen = []

    def air(text, card, speed):
        seen.append(text)
        return (wav or _sine_wav(0.5)), 24000
    monkeypatch.setitem(voiceout._SYNTH, "air", air)
    monkeypatch.setattr(voiceout, "read_prefs",
                        lambda: {"rev": 1, "cards": {},
                                 "global": {"speed": 1.0, "chunk": "sentence"},
                                 "modes": {}})   # stub pins the lane each test
    return seen                                  # asserts on (chunk_mode reads
                                                 # this stub via read_prefs)


def _sandbox_home(monkeypatch):
    """Point the plugin's prefs root at a throwaway dir so voiceout state writes
    never land in whoever's home runs pytest. The resolver prefers a CONTEXT-LOCAL
    override and profile-pin env vars over HERMES_HOME — neutralize all three
    (exact conftest pattern, 2026-11-10 lesson)."""
    import tempfile
    d = tempfile.mkdtemp(prefix="wearos-home-")
    monkeypatch.setenv("HERMES_HOME", d)
    try:
        import hermes_constants as _hc
        if hasattr(_hc, "set_hermes_home_override"):
            _token = _hc.set_hermes_home_override(d)
            monkeypatch.finalize(lambda: _hc.reset_hermes_home_override(_token))
    except Exception:
        pass
    monkeypatch.delenv("HERMES_GATEWAY_PROFILE_PIN", raising=False)
    monkeypatch.delenv("HERMES_SESSION_PROFILE", raising=False)
    return d


def test_stream_yields_one_pcm_chunk_per_sentence(monkeypatch):
    _sandbox_home(monkeypatch)
    seen = _fake_engine(monkeypatch, None)
    import voiceout
    voiceout.set_chunk_mode("sentence")   # this test pins the SENTENCE lane;
    # product default became 'paragraph' 2026-11-11 (see test_chunk_mode.py).
    p = tts_provider.WearKitGatewayProvider()
    chunks = list(p.stream(LONG3, speed=None))
    assert len(seen) == 3 and seen == voiceout_sentences()
    assert len(chunks) == 3
    for c in chunks:
        assert isinstance(c, bytes) and len(c) % 2 == 0 and len(c) > 0
    with wave.open(io.BytesIO(_sine_wav(0.5))) as w:
        expected = len(w.readframes(w.getnframes()))
    assert all(len(c) == expected for c in chunks)      # dial 1.0 = pass-through


def voiceout_sentences():
    import voiceout
    return voiceout._sentences(LONG3)


def test_stream_applies_speed_to_raw_pcm(monkeypatch):
    _fake_engine(monkeypatch, None)
    p = tts_provider.WearKitGatewayProvider()
    chunks = list(p.stream("One short sentence.", speed=1.5))
    assert len(chunks) == 1
    assert abs(len(chunks[0]) - int(0.5 * 24000 * 2 / 1.5)) < 2400 * 2  # 1.5x shorter


def test_stream_rate_drift_raises_not_garble(monkeypatch):
    import voiceout
    _no_cache(monkeypatch)
    monkeypatch.setitem(voiceout._SYNTH, "air",
                        lambda text, card, speed: (_sine_wav(0.4, rate=16000), 16000))
    monkeypatch.setattr(voiceout, "read_prefs",
                        lambda: {"rev": 1, "cards": {}, "global": {"speed": 1.0},
                                 "modes": {}})
    p = tts_provider.WearKitGatewayProvider()
    try:
        list(p.stream("Drift check.", speed=None))
        assert False, "a wrong rate must raise, never play chipmunk"
    except RuntimeError as exc:
        assert "drift" in str(exc)


def test_resolve_voice_falls_back_to_the_single_card(monkeypatch):
    import voiceout
    monkeypatch.setattr(voiceout, "read_prefs", lambda: {
        "rev": 3, "cards": {"clay-synth-eff920cd": {"label": "clay-synth"}},
        "voices": {"__default__": "clay-synth-eff920cd"}, "global": {}, "modes": {}})
    p = tts_provider.WearKitGatewayProvider()
    assert p._resolve_voice(None) == "clay-synth-eff920cd"
    assert p._resolve_voice("explicit-card") == "explicit-card"
