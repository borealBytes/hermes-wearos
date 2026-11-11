"""voiceout core: cards, measured ref_rate, rev'd prefs, honest refusals (issue #7)."""
import importlib.util, math, os, struct, tempfile, unittest
import threading as _th, time as _t
os.environ.setdefault("HERMES_HOME", tempfile.mkdtemp(prefix="vo-test-"))
_spec = importlib.util.spec_from_file_location(
    "voiceout", os.path.join(os.path.dirname(__file__), "..", "voiceout.py"))
vo = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(vo)

def mkwav(segs, rate=16000):
    sm = []
    for sec, amp in segs:
        for i in range(int(sec * rate)):
            v = (int(amp * 12000 * math.sin(2 * math.pi * 220 * i / rate)) if amp
                 else int(40 * math.sin(2 * math.pi * 60 * i / rate)))
            sm.append(v)
    data = b"".join(struct.pack("<h", s) for s in sm)
    hdr = (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt "
           + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
           + b"data" + struct.pack("<I", len(data)))
    return hdr + data

VOICED = mkwav([(0.3, 0), (3.5, 1.0), (0.6, 0)])          # 3.5 s of "speech"


def _use_home(d):
    """Point the plugin's prefs/cache root at d for THIS test, host-proof: the
    resolver prefers a CONTEXT-LOCAL override over HERMES_HOME (exact conftest
    pattern), so set it ourselves — a stale override leaked by an earlier test
    file must never re-point our sandbox (2026-11-11 lesson: pytest 9 dropped
    MonkeyPatch.finalize and one reset silently died). Returns (hc, token)."""
    os.environ["HERMES_HOME"] = d
    try:
        import hermes_constants as hc
        tok = hc.set_hermes_home_override(d) if hasattr(
            hc, "set_hermes_home_override") else None
        return hc, tok
    except Exception:
        return None, None


def _drop_home(hc_tok):
    hc, tok = hc_tok
    if hc is not None and tok is not None:
        try:
            hc.reset_hermes_home_override(tok)
        except ValueError:
            hc.set_hermes_home_override(None)


class VoiceoutTests(unittest.TestCase):
    def setUp(self):
        self._home = _use_home(tempfile.mkdtemp(prefix="vo-test-"))

    def tearDown(self):
        _drop_home(self._home)

    def test_wav_reader(self):
        self.assertAlmostEqual(vo._wav_duration(VOICED), 4.4, places=1)
        self.assertAlmostEqual(vo._voiced_seconds(VOICED), 3.5, places=1)

    def test_vad_bridges_conversation_not_coughs(self):
        # Real speech drops below gate between syllables; a <=300ms dip is
        # coarticulation (part of the voice span), a 600ms dip is a breath or
        # a cough (silence). The first real-voice card read 567 wpm because
        # the old gate counted neither dip as voice at all.
        gappy = mkwav([(0.2, 0), (0.5, 1.0), (0.15, 0), (0.5, 1.0), (0.2, 0)])
        self.assertGreaterEqual(vo._voiced_seconds(gappy), 1.05)   # gap bridged
        breath = mkwav([(0.2, 0), (0.5, 1.0), (0.6, 0), (0.5, 1.0), (0.2, 0)])
        self.assertLess(abs(vo._voiced_seconds(breath) - 1.0), 0.08)  # not bridged

    def test_card_measures_not_assumes(self):
        c = vo.create_card(VOICED, "Test Pipe")
        expect = len(vo.WORD_RE.findall(vo.SCRIPTED_PROMPT)) / 3.5 * 60
        self.assertAlmostEqual(c["ref_rate"], expect, delta=15)
        self.assertTrue(3.0 <= c["voiced_s"] <= 3.6)

    def test_three_second_floor(self):
        with self.assertRaises(ValueError):
            vo.create_card(mkwav([(1.0, 1.0)]), "Short")

    def test_global_speed_and_rev(self):
        p = vo.set_speed(1.4)
        self.assertEqual(p["global"]["speed"], 1.4)
        r1 = p["rev"]
        p = vo.set_mode("farmbot", "gateway")
        self.assertGreater(p["rev"], r1)
        with self.assertRaises(KeyError):
            vo.set_voice("farmbot", "no-such-card")

    def test_rev_merge_wins_both_directions(self):
        pr = vo.set_speed(1.0)
        newer = dict(pr); newer["rev"] = pr["rev"] + 5
        newer["global"] = dict(pr["global"]); newer["global"]["speed"] = 0.7
        m = vo.merge_prefs(newer)
        self.assertEqual(m["global"]["speed"], 0.7)      # watch (higher rev) wins
        older = dict(m); older["rev"] = 2
        older["global"] = dict(older["global"]); older["global"]["speed"] = 2.0
        self.assertEqual(vo.merge_prefs(older)["global"]["speed"], 0.7)  # stale loses

    def test_no_engine_refuses_honestly(self):
        vo.set_mode("farmbot", "gateway")
        r = vo.synthesize("hello", "farmbot")
        self.assertFalse(r["ok"]); self.assertIn("engine", r["error"])

    def test_watch_mode_ships_contract_not_audio(self):
        vo.set_mode("farmbot", "watch")
        r = vo.synthesize("hello", "farmbot")
        self.assertTrue(r["ok"]); self.assertEqual(r["tier"], "watch")
        self.assertEqual(r["karaoke"], "sentence")       # honest unit for this tier

    def test_card_id_override_for_auditions(self):
        # The picker's ▶ auditions ANY card at the live dial speed: an explicit card_id
        # overrides the agent's bound voice; an unknown card is a refusal, never a
        # silent fall-through to the bound one.
        c = vo.create_card(VOICED, "Audition")
        vo.set_mode("farmbot", "gateway")
        r = vo.synthesize("hello", "farmbot", card_id=c["card_id"])
        self.assertFalse(r["ok"]); self.assertIn("engine", r["error"])
        with self.assertRaises(ValueError):
            vo.synthesize("hello", "farmbot", card_id="ghost-card")

    def test_delete_unbinds_never_orphans(self):
        c = vo.create_card(VOICED, "Doomed")
        vo.set_voice("farmbot", c["card_id"])
        p = vo.delete_card(c["card_id"])
        self.assertEqual(p["voices"]["farmbot"], "")
        self.assertNotIn(c["card_id"], p["cards"])


LONG3 = ("The wear kit renders every long reply sentence by sentence now. "
         "Each sentence is its own short generation with its own cache entry, "
         "so repeated sentences across any future reply replay instantly from disk. "
         "And the engine never locks up on one giant paragraph again, which is "
         "exactly what the owner ordered on this fine November afternoon.")


class SentenceChunkingTests(unittest.TestCase):
    """Owner ruling 2026-11-10: sentence-by-sentence in BOTH lanes."""

    def setUp(self):
        self._home = _use_home(tempfile.mkdtemp(prefix="vo-test-"))
        # These are the issue #7 SENTENCE-mode tests; paragraph became the
        # product default on 2026-11-11 (see test_chunk_mode.py for that lane).
        vo.set_chunk_mode("sentence")
        self.calls = []
        vo._SYNTH["air"] = self._air
        self._align = dict(vo._ALIGN)

    def _drain_audition(self):
        """create_card warms the audition clip on a daemon thread (voiceout
        340-361): it lands a draw in self.calls ASYNC — join it before any
        call-count snapshot or the count races (owner's CI-vs-local flake)."""
        deadline = _t.time() + 2.0
        while _t.time() < deadline:
            th = [x for x in _th.enumerate() if x.name.startswith("voiceout-audition-warm")]
            if not th:
                return
            th[0].join(0.1)

    def tearDown(self):
        _drop_home(self._home)
        vo._SYNTH.pop("air", None)
        vo._ALIGN.clear(); vo._ALIGN.update(self._align)

    def _air(self, text, card, speed):
        self.calls.append(text)
        return mkwav([(0.5, 1.0)], rate=24000), 24000

    def test_short_text_stays_one_clip(self):
        self.assertEqual(vo._sentences("Short and sweet."), ["Short and sweet."])

    def test_unbound_agent_falls_back_to_the_global_card(self):
        # Owner bug 2026-11-10: pane POSTs agent='desktop', the voices map only
        # carried '__default__' — the speakable box answered a refusal. The
        # dropdown's selection IS the fallback voice for unbound agents.
        c = vo.create_card(VOICED, "Global")
        vo.set_voice("__default__", c["card_id"])
        self._drain_audition()                # async warm: settle before counting
        before = len(self.calls)              # create_card may audition once
        r = vo.synthesize("Knock knock.", "desktop", want_words=False)
        self.assertTrue(r["ok"], r.get("error"))
        self.assertEqual(len(self.calls) - before, 1)

    def test_long_reply_splits_per_sentence(self):
        segs = vo._sentences(LONG3)
        self.assertEqual(len(segs), 3)
        r = vo.synthesize(LONG3, "farmbot", want_words=False)
        self.assertTrue(r["ok"])
        self.assertEqual(self.calls, segs)               # one short draw each
        import io as _io, wave
        with wave.open(_io.BytesIO(r["audio"])) as w:
            dur = w.getnframes() / float(w.getframerate())
        self.assertAlmostEqual(dur, 3 * 0.5 + 2 * 0.08, places=1)  # stitched + breaths

    def test_stray_fragments_merge_forward(self):
        t = "Yes. No. " + LONG3
        self.assertEqual(len(vo._sentences(t)), 3)       # 'Yes. No.' joined onward

    def test_repeated_sentence_renders_once_ever(self):
        vo.synthesize(LONG3, "farmbot", want_words=False)
        self.assertEqual(len(self.calls), 3)
        r = vo.synthesize(LONG3, "farmbot", want_words=False)  # whole-text cache hit
        self.assertTrue(r.get("cached"))
        # a NEW reply containing two already-heard sentences reuses them
        new = LONG3.split(" And ")[0] + " A brand new closing sentence appears here, and it is comfortably over the minimum fragment length to stand alone."
        before = len(self.calls)
        vo.synthesize(new, "farmbot", want_words=False)
        self.assertEqual(len(self.calls) - before, 1)    # only the fresh sentence

    def test_karaoke_timeline_survives_the_stitch(self):
        def fake_align(audio, rate, text):
            return [{"w": text.split()[0], "t0": 0.0, "t1": 0.4}]
        vo._ALIGN["parakeet"] = fake_align
        r = vo.synthesize(LONG3, "farmbot", want_words=True)
        ws = r["words"]
        self.assertEqual(len(ws), 3)
        self.assertAlmostEqual(ws[0]["t0"], 0.0, places=3)
        self.assertAlmostEqual(ws[1]["t0"], 0.58, places=3)   # clip1 + breath gap
        self.assertAlmostEqual(ws[2]["t0"], 1.16, places=3)

if __name__ == "__main__":
    unittest.main(verbosity=2)
