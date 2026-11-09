"""align_parakeet: honest availability + the script-blend math (issue #7 s2b)."""
import importlib.util, json, os, struct, tempfile, unittest
os.environ.setdefault("HERMES_HOME", tempfile.mkdtemp(prefix="al-test-"))
_spec = importlib.util.spec_from_file_location(
    "align_parakeet", os.path.join(os.path.dirname(__file__), "..", "align_parakeet.py"))
al = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(al)


class AlignTests(unittest.TestCase):
    def setUp(self):
        os.environ["HERMES_HOME"] = tempfile.mkdtemp(prefix="al-test-")

    def test_absent_engine_is_honest_not_silent(self):
        self.assertFalse(al.engine_ready())          # nothing installed in the sandbox
        self.assertFalse(al.register_if_ready())
        st = al.status()
        self.assertFalse(st["ready"])
        with self.assertRaises(RuntimeError) as cm:
            al.align(b"RIFFxxxx", 24000, "hello world")
        self.assertIn("not installed", str(cm.exception))   # named cause

    def test_fit_measured_rows_carry_the_script(self):
        rows = [{"text": "three", "start": 0.10, "end": 0.42},
                {"text": "farm", "start": 0.42, "end": 0.80},
                {"text": "alerts", "start": 0.80, "end": 1.30}]
        out = al._fit_to_script(rows, "three farm alerts.", 2.0)
        # karaoke shows the SCRIPT truth — punctuation stays on its word
        self.assertEqual([w["text"] for w in out], ["three", "farm", "alerts."])
        self.assertAlmostEqual(out[0]["start"], 0.10)
        self.assertGreater(out[2]["end"], out[1]["end"])
        self.assertNotIn("align", out[0])                 # measured rows carry no flag

    def test_recognized_gap_gets_estimated_slot_between_neighbours(self):
        # script has "cron" the recognizer dropped between the first two words
        rows = [{"text": "your", "start": 0.0, "end": 0.2},
                {"text": "digest", "start": 1.0, "end": 1.4}]
        out = al._fit_to_script(rows, "your cron digest", 2.0)
        self.assertEqual([w["text"] for w in out], ["your", "cron", "digest"])
        self.assertEqual(out[1].get("align"), "estimated")
        self.assertGreaterEqual(out[1]["start"], out[0]["end"] - 0.001)
        self.assertLessEqual(out[1]["end"], out[2]["start"] + 0.001)

    def test_no_rows_goes_proportional_not_empty(self):
        out = al._fit_to_script([], "one two three four", 4.0)
        self.assertEqual(len(out), 4)
        self.assertTrue(all(w.get("align") == "estimated" for w in out))
        self.assertAlmostEqual(out[0]["start"], 0.0, places=2)
        self.assertAlmostEqual(out[-1]["end"], 4.0, delta=0.05)

    def test_monotonic_and_clamped(self):
        rows = [{"text": "a", "start": 5.0, "end": 5.2},      # beyond audio!
                {"text": "b", "start": 0.0, "end": 0.3}]      # out of order
        out = al._fit_to_script(rows, "a b", 1.0)
        for w in out:
            self.assertLessEqual(w["end"], 1.0)
            self.assertGreaterEqual(w["start"], 0.0)
            self.assertGreater(w["end"], w["start"])

    def test_punctuation_case_ignored_in_matching(self):
        rows = [{"text": "Sixteen.", "start": 0.0, "end": 0.5}]
        out = al._fit_to_script(rows, "sixteen", 1.0)
        self.assertEqual(len(out), 1)
        self.assertNotIn("align", out[0])                     # matched clean

    def test_runner_source_compiles(self):
        import py_compile
        f = tempfile.NamedTemporaryFile(suffix=".py", delete=False)
        f.write(al.ALIGNER_SRC.encode()); f.close()
        try:
            py_compile.compile(f.name, doraise=True)
        finally:
            os.unlink(f.name)

    def test_end_to_end_through_voiceout_contract(self):
        """voiceout.synthesize must report align:"forced" with words when the
        aligner works, align:"none" when it raises — never a silent middle."""
        vo_spec = importlib.util.spec_from_file_location(
            "voiceout", os.path.join(os.path.dirname(__file__), "..", "voiceout.py"))
        vo = importlib.util.module_from_spec(vo_spec)
        vo_spec.loader.exec_module(vo)
        vo._SYNTH.clear(); vo._ALIGN.clear()
        text = "Your cron digest is ready today"
        fake_wav = b"RIFF" + struct.pack("<I", 36 + 4800) + b"WAVEfmt " \
            + struct.pack("<IHHIIHH", 16, 1, 1, 24000, 48000, 2, 16) \
            + b"data" + struct.pack("<I", 4800) + b"\x00" * 4800
        vo._SYNTH["air"] = lambda t, c, s: (fake_wav, 24000)
        vo._ALIGN["parakeet"] = lambda a, r, t: [
            {"text": w, "start": i * 0.5, "end": i * 0.5 + 0.4}
            for i, w in enumerate(text.split())]
        r = vo.synthesize(text, "wear")
        self.assertEqual(r["align"], "forced")
        self.assertEqual(len(r["words"]), 6)

        def boom(a, r_, t):
            raise RuntimeError("aligner failed: segfault")
        vo._ALIGN["parakeet"] = boom
        r = vo.synthesize(text, "wear")
        self.assertEqual(r["align"], "none")                   # honest degradation
        self.assertTrue(r["ok"])                               # audio still ships


if __name__ == "__main__":
    unittest.main(verbosity=2)
