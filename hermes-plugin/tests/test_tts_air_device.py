"""SPEC 19.4(m) — device choice: CPU floor, GPU by pane pick, GPU0 shield.

The device switch must never lie: it reports the QUEUED side, not the wish,
until the CUDA variant is actually on disk; and the only fence between the
engine and the box's busiest GPU is CUDA_VISIBLE_DEVICES at spawn.
"""
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


@pytest.fixture()
def ta(tmp_path, monkeypatch):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    import tts_air
    # a GPU pick in tests must not launch a real venv build thread
    calls = []
    monkeypatch.setattr(tts_air, "build_cuda_gpu", lambda idx: calls.append(idx) or {})
    tts_air._CUDA_STATE.update(phase="idle", detail="")
    yield tts_air
    del calls


def test_default_is_cpu(ta):
    assert ta.get_device() == "cpu"


def test_roundtrip_and_validation(ta):
    assert ta.set_device("gpu:1")["ok"]
    assert ta.get_device() == "gpu:1"
    assert not ta.set_device("sideways")["ok"]
    assert ta.get_device() == "gpu:1"            # refusal changed nothing


def test_effective_stays_cpu_until_cuda_venv_exists(ta):
    ta.set_device("gpu:1")
    assert ta.effective_device() == "cpu"        # queued, not a lie
    cu = ta._root() / "venv_cu"
    cu.mkdir(parents=True)
    (cu / ".cuda-installed").write_text("x")
    assert ta.effective_device() == "cuda"


def test_gpu_pick_starts_the_one_time_build(ta, monkeypatch):
    seen = []
    monkeypatch.setattr(ta, "build_cuda_gpu", lambda idx: seen.append(idx))
    ta.set_device("gpu:1")
    assert seen == [1]
    ta.set_device("cpu")
    assert seen == [1]                            # leaving the GPU builds nothing


def test_serve_argv_names_device_and_venv(ta):
    argv = ta._serve_argv(7)
    assert argv[argv.index("--device") + 1] == "cpu"
    assert "venv_cu" not in argv[0]
    (ta._root() / "venv_cu").mkdir(parents=True)
    (ta._root() / "venv_cu" / ".cuda-installed").write_text("x")
    ta.set_device("gpu:1")
    argv = ta._serve_argv(7)
    assert argv[argv.index("--device") + 1] == "cuda"
    assert "venv_cu" in argv[0]


def test_runner_env_fences_the_visible_cards(ta):
    env = ta._runner_env("gpu:1")
    assert env["CUDA_VISIBLE_DEVICES"] == "1"     # the GPU0 shield
    assert "CUDA_VISIBLE_DEVICES" not in ta._runner_env("cpu")


def test_devices_always_offers_cpu(ta):
    rows = ta.devices()
    assert rows[0]["id"] == "cpu" and rows[0]["ready"] is True
