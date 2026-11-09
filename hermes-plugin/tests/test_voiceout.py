"""voiceout core: cards, measured ref_rate, rev'd prefs, honest refusals (issue #7)."""
import importlib.util, math, os, struct, tempfile, unittest
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

class VoiceoutTests(unittest.TestCase):
    def setUp(self):
        os.environ["HERMES_HOME"] = tempfile.mkdtemp(prefix="vo-test-")

    def test_wav_reader(self):
        self.assertAlmostEqual(vo._wav_duration(VOICED), 4.4, places=1)
        self.assertAlmostEqual(vo._voiced_seconds(VOICED), 3.5, places=1)

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

    def test_delete_unbinds_never_orphans(self):
        c = vo.create_card(VOICED, "Doomed")
        vo.set_voice("farmbot", c["card_id"])
        p = vo.delete_card(c["card_id"])
        self.assertEqual(p["voices"]["farmbot"], "")
        self.assertNotIn(c["card_id"], p["cards"])

if __name__ == "__main__":
    unittest.main(verbosity=2)
