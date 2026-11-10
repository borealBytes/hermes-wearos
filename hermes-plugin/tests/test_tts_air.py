"""tts_air: honest probe, size-gated fetch, stdlib time-scale, no-network (issue #7 s2)."""
import hashlib, importlib.util, os, shutil, struct, sys, tempfile, unittest
from unittest import mock
os.environ.setdefault("HERMES_HOME", tempfile.mkdtemp(prefix="tts-test-"))


def _load(name):
    spec = importlib.util.spec_from_file_location(
        name, os.path.join(os.path.dirname(__file__), "..", name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ta = _load("tts_air")
vo = _load("voiceout")


class TtsAirTests(unittest.TestCase):
    def setUp(self):
        os.environ["HERMES_HOME"] = tempfile.mkdtemp(prefix="tts-test-")
        os.environ["HF_TOKEN"] = ""                           # neutral, whatever the host has

    # ---------------------------------------------- pins & probe honesty

    def test_pins_are_shaped_and_documented(self):
        self.assertEqual(len(ta.COMMIT), 40)
        int(ta.COMMIT, 16)                                   # hex
        self.assertGreater(ta.MODEL_BYTES, 10_000_000)        # an 800 MB-scale model
        self.assertIn("neutts-air-Q8_0.gguf", ta.MODEL_URL)
        self.assertTrue(ta.MODEL_SHA256 == "PENDING"
                        or len(ta.MODEL_SHA256) == 64)         # honest, not invented
        self.assertEqual(ta.OUTPUT_RATE, 24_000)

    def test_probe_empty_home_is_honest(self):
        st = ta.status()
        self.assertFalse(st["installed"])
        self.assertFalse(st["model"])
        self.assertFalse(st["model_verified"])
        self.assertFalse(st.get("verified_pending"))
        self.assertFalse(st["hf_token"])                      # no anonymous token claims
        self.assertEqual(st["phase"], "idle")

    def test_start_refuses_without_terminal_or_token(self):
        r = ta.start(interactive=False)
        self.assertFalse(r["started"])
        self.assertIn("rule 12", r["error"])                  # named cause, not silence
        r = ta.start(interactive=True, source="official-q8")   # gated row, no token
        self.assertFalse(r["started"])
        self.assertTrue(("consent" in r["error"]) or ("toolchain" in r["error"])
                        or ("g++" in r["error"]))

    def test_size_gate_and_pending_sha_honesty(self):
        src = ta.SOURCES["community-q8"]
        old = src["bytes"]
        src["bytes"] = 12
        try:
            model = ta._root() / "model" / src["file"]
            model.parent.mkdir(parents=True, exist_ok=True)
            model.write_bytes(b"0" * 11)                       # one byte short
            p = ta._probe()
            self.assertFalse(p["model"])                       # size lies: not accepted
            model.write_bytes(b"0" * 12)
            p = ta._probe()
            self.assertTrue(p["model"])
            self.assertFalse(p["model_verified"])              # PENDING never claims
            self.assertTrue(p["verified_pending"])             # …it verified
            real = hashlib.sha256(b"0" * 12).hexdigest()
            old_sha = src.get("sha256", "PENDING")
            src["sha256"] = real
            try:
                p = ta._probe()
                self.assertTrue(p["model_verified"])
                self.assertFalse(p["verified_pending"])
            finally:
                src.pop("sha256") if old_sha == "PENDING" else src.update(sha256=old_sha)
        finally:
            src["bytes"] = old

    def test_register_if_ready_false_when_absent(self):
        self.assertFalse(ta.register_if_ready())
        self.assertNotIn("air", vo._SYNTH)                     # refusal stays honest

    # ---------------------------------------------- source menu (owner ruling)

    def test_sources_menu_community_default_and_two_slots(self):
        menu = ta.sources_menu()
        self.assertGreaterEqual(len(menu), 4)
        for row in menu:
            for k in ("id", "label", "bytes", "gated", "selected_for", "present"):
                self.assertIn(k, row)
        sel = {t: [r["id"] for r in menu if t in r["selected_for"]] for t in ta.SLOTS}
        self.assertEqual(sel["gateway"], ["community-q8"])   # best community quant,
        self.assertEqual(sel["watch"], ["community-q4"])     # by default; watch = Q4
        self.assertTrue(any(r["gated"] and r["id"] == "official-q8" for r in menu))

    def test_codec_is_official_repo_until_a_port_is_verified(self):
        """bezzam/neucodec-hf LOOKS like the repo but ISN'T the package's shape
        (flat acoustic_* keys, 517-key w2v-Bert frontend, no pytorch_model.bin):
        the loader roundtrip encoded every frame of real speech to ONE code.
        Both loaders also hard-assert the gated ids. No row may go back to the
        port until a roundtrip passes against it (measured 2026-11-09)."""
        for sid, row in ta.SOURCES.items():
            self.assertEqual(row["codec"]["kind"], "repo", sid)
            self.assertEqual(row["codec"]["repo"], ta.CODEC_REPO, sid)

    def test_select_source_moves_selection_and_rejects_invention(self):
        r = ta.select_source("alt-q8-drinking", for_slot="gateway")
        self.assertTrue(r["ok"])
        row = [m for m in ta.sources_menu() if m["id"] == "alt-q8-drinking"][0]
        self.assertEqual(row["selected_for"], ["gateway"])   # watch keeps its Q4
        bad = ta.select_source("mirror-from-a-sketchy-telegram-group")
        self.assertFalse(bad["ok"])
        self.assertIn("community-q8", bad["error"])          # the menu names its rows

    def test_default_community_source_clears_the_consent_gate(self):
        real_which = ta.shutil.which
        ta.shutil.which = lambda n: "/usr/bin/" + n            # pretend a full toolchain
        real_thread = ta.threading.Thread
        ta.threading.Thread = lambda **kw: type("T", (), {"start": lambda s: None,
                                                          "is_alive": lambda s: False})()
        try:
            os.environ["HF_TOKEN"] = ""
            default = ta.start(interactive=True)               # community q8
            self.assertTrue(default.get("started"),
                            f"the default source must need no account: {default}")
            gated = ta.start(interactive=True, source="official-q8")
            self.assertFalse(gated["started"])
            self.assertIn("consent", gated["error"])
            watch = ta.start(interactive=True, source="official-q4", for_slot="watch")
            self.assertFalse(watch["started"])                 # watch slot records only
            self.assertIn("slice 5", watch["note"])
            self.assertEqual(ta._source_id("watch"), "official-q4")
        finally:
            ta.shutil.which = real_which
            ta.threading.Thread = real_thread

    # ---------------------------------------------- synth seam

    def test_synth_raises_named_cause_when_not_installed(self):
        with self.assertRaises(RuntimeError) as cm:
            ta.synth("hello", {"card_id": "x", "transcript": "y"}, 1.0)
        self.assertIn("not installed", str(cm.exception))

    def test_argv_is_fixed_and_text_rides_files(self):
        out = ta._root() / "s.wav"
        argv = ta._synth_argv("rm -rf $HOME ; echo hi",
                              ta._root() / "ref.wav", "some words", out)
        self.assertIsInstance(argv, list)
        self.assertTrue(all(isinstance(a, str) for a in argv))
        self.assertNotIn("rm -rf $HOME ; echo hi", argv)       # text NEVER an argv value
        joined = " ".join(argv)
        for shelly in (";", "$", "|", "`"):
            self.assertNotIn(shelly, joined.replace("$HOME", ""))

    def test_runner_source_compiles(self):
        import py_compile, tempfile as tf
        src = tf.NamedTemporaryFile(suffix=".py", delete=False)
        src.write(ta.RUNNER_SRC.encode()); src.close()
        try:
            py_compile.compile(src.name, doraise=True)
        finally:
            os.unlink(src.name)

    # ---------------------------------------------- time-scale (stdlib math)

    def test_time_scale_identity_and_halving(self):
        pcm = b"".join(struct.pack("<h", s) for s in range(-100, 100))   # 200 samples
        self.assertEqual(ta._time_scale(pcm, 1.0), pcm)
        fast = ta._time_scale(pcm, 2.0)
        self.assertLess(abs(len(fast) - 200), 4)               # ~100 samples = 200 B
        self.assertEqual(len(fast) % 2, 0)                     # whole 16-bit frames
        first, last = struct.unpack("<hh", fast[:2] + fast[-2:])
        self.assertAlmostEqual(first, -100, delta=2)           # covers the START…
        self.assertAlmostEqual(last, 99, delta=2)              # …and the END, not a slice
        slow = ta._time_scale(pcm, 0.5)
        self.assertGreater(len(slow), len(pcm))

    # ---------------------------------------------- voiceout seam the adapter needs

    def test_card_persists_its_transcript(self):
        wav = vo_create_wav()
        c = vo.create_card(wav, "Pipe A")
        self.assertEqual(c["transcript"], vo.SCRIPTED_PROMPT)  # measured-against truth
        c2 = vo.create_card(wav, "Seed B", source="seed-pack",
                            transcript="totally other words")
        self.assertEqual(c2["transcript"], "totally other words")

    def test_engine_receives_card_id(self):
        captured = {}
        def fake_engine(text, card, speed):
            captured.update(card=card, speed=speed)
            return b"RIFFfake", 24000
        vo._SYNTH["air"] = fake_engine
        try:
            wav = vo_create_wav()
            c = vo.create_card(wav, "Bound")
            vo.set_voice("wear", c["card_id"])                 # bumps rev, canonical
            r = vo.synthesize("say this", "wear")
            self.assertTrue(r["ok"])
            self.assertEqual(captured["card"]["card_id"], c["card_id"])
        finally:
            vo._SYNTH.pop("air", None)

    def test_venv_python_stays_inside_the_neutts_pin(self):
        # The private venv must never silently fall onto an out-of-range host
        # interpreter (measured: 3.14.9 rejects the pinned wheel).
        for fake in (None, "3.9", "3.14"):
            with self.subTest(host=fake):
                found = "/usr/bin/python" + fake if fake else None
                vi = mock.Mock(major=3, minor=int(fake.split(".")[1])) if fake else mock.Mock(major=3, minor=14)
                with mock.patch.object(shutil, "which",
                                       return_value=found), \
                     mock.patch.object(sys, "version_info", vi):
                    if fake:
                        self.assertEqual(ta._venv_python(), found)
                    else:
                        with self.assertRaises(RuntimeError) as cm:
                            ta._venv_python()
                        self.assertIn("3.10-3.13", str(cm.exception))




def vo_create_wav():
    """Amplitude-modulated speech-like envelope (slice-1 fixture shape)."""
    import math
    rate, sm = 16000, []
    for i in range(int(4.4 * rate)):
        t = i / rate
        env = 0.15 + 0.85 * (0.5 + 0.5 * math.sin(2 * math.pi * 3.1 * t)) if 0.3 <= t <= 4.0 else 0.02
        sm.append(int(env * 9000 * math.sin(2 * math.pi * 220 * i / rate)))
    data = b"".join(struct.pack("<h", s) for s in sm)
    hdr = (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt "
           + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
           + b"data" + struct.pack("<I", len(data)))
    return hdr + data
