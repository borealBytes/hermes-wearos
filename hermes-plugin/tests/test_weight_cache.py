"""weight_cache: hash-match skips the bytes, interrupts resume, pins protect."""
import os, tempfile, unittest
from pathlib import Path
from unittest import mock

os.environ.setdefault("HERMES_HOME", tempfile.mkdtemp(prefix="wc-test-"))

def _load(name):
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        name, os.path.join(os.path.dirname(__file__), "..", name + ".py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

wc = _load("weight_cache")

URL = "https://huggingface.co/mradermacher/neutts-air-GGUF/resolve/main/neutts-air.Q8_0.gguf"


class Resp:
    def __init__(self, headers, body=b""):
        self.headers = headers
        self._body = body
        self._i = 0
    def read(self, n=0):
        out = self._body[self._i:self._i + max(1, n)] if n else self._body[self._i:]
        self._i += len(out)
        return out
    def __enter__(self): return self
    def __exit__(self, *a): return False


class WeightCacheTests(unittest.TestCase):
    def setUp(self):
        import shutil
        self.home = Path(tempfile.mkdtemp(prefix="wc-home-"))
        self.addCleanup(shutil.rmtree, self.home, True)
        self.sha_abcd = __import__("hashlib").sha256(b"abcd").hexdigest()

    def test_key_isolates_repos(self):
        self.assertEqual(wc.key_for(URL),
                         "mradermacher--neutts-air-GGUF--neutts-air.Q8_0.gguf")

    def test_hash_matched_entry_hardlinks_never_refetches(self):
        ent = self.home / wc.key_for(URL)
        ent.write_bytes(b"abcd")
        wc.record(self.home, wc.key_for(URL), {"bytes": 4, "sha256": self.sha_abcd, "url": URL})
        calls = []
        def opener(req, timeout=None):
            calls.append(req.get_method())
            return Resp({"Content-Length": "4", "x-linked-etag": self.sha_abcd})
        dest = self.home.parent / "runtime.gguf"
        r = wc.fetch(self.home, URL, dest, 4, openner=opener)
        self.assertEqual(calls, ["HEAD"])          # no GET at all
        self.assertTrue(r["cache_hit"])
        self.assertEqual(dest.read_bytes(), b"abcd")
        self.assertGreater(ent.stat().st_nlink, 1)        # hardlinked, not doubled

    def test_size_change_sends_us_back_to_the_stream(self):
        # remote hash NOT matching (repo re-uploaded a bigger file) -> re-fetch.
        ent = self.home / wc.key_for(URL)
        ent.write_bytes(b"abcd")
        wc.record(self.home, wc.key_for(URL), {"bytes": 4, "sha256": self.sha_abcd, "url": URL})
        def opener(req, timeout=None):
            if req.get_method() == "HEAD":
                return Resp({"Content-Length": "8"})     # different size now
            return Resp({"Content-Length": "8"}, b"abcdEFGH")
        dest = self.home.parent / "runtime2.gguf"
        r = wc.fetch(self.home, URL, dest, 8, openner=opener)
        self.assertFalse(r["cache_hit"])
        self.assertEqual(dest.read_bytes(), b"abcdEFGH")

    def test_partial_resumes_with_range(self):
        seen = {}
        def opener(req, timeout=None):
            seen["range"] = req.get_header("Range")
            return Resp({"Content-Range": "bytes 4-/8", "Content-Length": "4"}, b"EFGH")
        ent = self.home / wc.key_for(URL)
        ent.with_suffix(ent.suffix + ".part").write_bytes(b"abcd")
        dest = self.home.parent / "runtime3.gguf"
        r = wc.fetch(self.home, URL, dest, 8, openner=opener)
        self.assertEqual(seen["range"], "bytes=4-")
        self.assertEqual(dest.read_bytes(), b"abcdEFGH")

    def test_pinned_sha_mismatch_keeps_nothing(self):
        def opener(req, timeout=None):
            return Resp({"Content-Length": "4"}, b"zzzz")
        with self.assertRaises(RuntimeError):
            wc.fetch(self.home, URL, self.home.parent / "x.gguf", 4,
                     expected_sha=self.sha_abcd, openner=opener)
        part = (self.home / wc.key_for(URL)).with_suffix(".gguf.part")
        self.assertFalse(part.exists())

    def test_short_stream_keeps_the_partial_for_resume(self):
        def opener(req, timeout=None):
            return Resp({"Content-Length": "8"}, b"abc")      # server dies early
        with self.assertRaises(RuntimeError):
            wc.fetch(self.home, URL, self.home.parent / "y.gguf", 8, openner=opener)
        self.assertEqual((self.home / wc.key_for(URL)).with_suffix(".gguf.part").read_bytes(), b"abc")


class SttVariantTests(unittest.TestCase):
    def setUp(self):
        import shutil
        self.home = Path(tempfile.mkdtemp(prefix="stt-test-"))
        os.environ["HERMES_HOME"] = str(self.home / "h")
        import shutil as sh; self.addCleanup(sh.rmtree, self.home, True)

    def _mod(self):
        return _load("stt_install")

    def test_menu_shape_default_and_selection(self):
        m = self._mod()
        menu = m.variant_menu()
        self.assertEqual({r["id"] for r in menu},
                         {"q4_k_m", "q5_k_m", "q6_k", "q8_0", "f16", "f32"})
        self.assertTrue(next(r for r in menu if r["id"] == "q8_0")["selected"])
        self.assertEqual(m._selected_variant(), "q8_0")
        r = m.select_variant("q4_k_m")
        self.assertTrue(r["ok"]) and self.assertEqual(m._selected_variant(), "q4_k_m")
        self.assertFalse(m.select_variant("made-up")["ok"])
        self.assertIn("Q4_K_M", m.model_path().name)
        # aligner must follow the selection, not a hardcoded filename
        src = open(os.path.join(os.path.dirname(__file__), "..", "align_parakeet.py")).read()
        self.assertIn("_model_path()", src)          # aligner follows the selection

    def test_preview_and_status_speak_of_the_chosen_row(self):
        m = self._mod()
        m.select_variant("f16")
        prev = m.preview()
        self.assertTrue(any("F16.gguf" in s for s in prev["steps"]))
        st = m.status()
        self.assertEqual(st["variant"], "f16")
        self.assertEqual(len(st["variants"]), 6)
