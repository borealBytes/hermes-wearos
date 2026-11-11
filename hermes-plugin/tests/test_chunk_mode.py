"""Chunk mode (owner ruling 2026-11-11): 'sentence' vs 'paragraph' (default).
Paragraph-per-draw steadies prosody; the _PARA_CAP sentence fallback keeps the
issue #7 latency promise honest. Stdlib-only, HERMES_HOME sandboxed."""
import importlib.util, os, tempfile
os.environ.setdefault("HERMES_HOME", tempfile.mkdtemp(prefix="chunk-test-"))
_spec = importlib.util.spec_from_file_location(
    "voiceout", os.path.join(os.path.dirname(__file__), "..", "voiceout.py"))
vo = importlib.util.module_from_spec(_spec); _spec.loader.exec_module(vo)

P1 = ("The sprayer window opens Thursday at six ten and the soil probes report "
      "moisture climbing across all three south fields for the first time since "
      "the storm passed through early last month.")
P2 = ("Bring the drill rig forward one hundred meters before the crew arrives so "
      "the morning shift loses no time waiting on the operator.")
LONG = " ".join(f"Sentence number {i} about nitrogen rates keeps growing the paragraph."
                for i in range(1, 40))          # ~2.6k chars, no blank line

class TestChunk:
    def test_paragraph_default_groups_by_blank_lines(self):
        segs = vo._sentences(P1 + "\n\n" + P2)            # mode omitted = default
        assert segs == [P1, P2]

    def test_default_is_paragraph(self):
        assert vo.CHUNK_DEFAULT == "paragraph"

    def test_sentence_mode_still_cuts(self):
        segs = vo._sentences(LONG, mode="sentence")
        assert len(segs) >= 4 and all(s in LONG for s in segs)

    def test_long_paragraph_sentence_splits_under_cap(self):
        segs = vo._sentences(LONG, mode="paragraph")
        assert len(segs) >= 2
        assert all(len(s) <= vo._PARA_CAP + 200 for s in segs)   # no mega-draw returns

    def test_short_text_is_one_clip_either_mode(self):
        assert vo._sentences("Sixty is 12 times five.", mode="paragraph") == \
               ["Sixty is 12 times five."]
        assert vo._sentences("Sixty is 12 times five.", mode="sentence") == \
               ["Sixty is 12 times five."]

    def test_setter_roundtrip_status_and_refusal(self):
        st = vo.set_chunk_mode("sentence")
        assert st.get("rev", 0) >= 1 and vo.chunk_mode() == "sentence"
        assert vo.status()["chunk"] == "sentence"
        bad = vo.set_chunk_mode("stanza")
        assert bad.get("ok") is False and "chunk mode" in bad["error"]
        assert vo.chunk_mode() == "sentence"                     # refusal kept prior
