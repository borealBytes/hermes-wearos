"""Catalog review law (NousResearch/hermes-agent#151546, round 3, item 2):
third-party build/setup chains must run with an ALLOWLIST env, never the
inherited gateway environment — otherwise every provider key on the box is
in reach of a pinned repo's setup.py."""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import stt_install          # noqa: E402
import tts_air              # noqa: E402

CANARIES = ("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "API_SERVER_KEY",
            "HERMES_TAILNET_KEY", "AWS_SECRET_ACCESS_KEY")


def _seed(monkeypatch):
    for k in CANARIES:
        monkeypatch.setenv(k, "secret-" + k)
    monkeypatch.setenv("PATH", os.defpath)
    monkeypatch.setenv("HOME", "/root")


def test_stt_install_min_env_drops_provider_keys(monkeypatch):
    _seed(monkeypatch)
    env = stt_install._min_env()
    for k in CANARIES:
        assert k not in env, f"{k} leaked into the STT build chain"
    assert env["PATH"] == os.defpath and env["HOME"] == "/root"
    assert env["GIT_TERMINAL_PROMPT"] == "0"


def test_tts_build_env_drops_provider_keys(monkeypatch):
    _seed(monkeypatch)
    env = tts_air._min_env()
    for k in CANARIES:
        assert k not in env, f"{k} leaked into the TTS build chain"
    runner = tts_air._runner_env("cpu")
    for k in CANARIES:
        if k != "HF_TOKEN":
            assert k not in runner, f"{k} leaked into the TTS worker"
    assert runner["HF_HOME"].endswith("cache")           # ours, explicit
    assert runner["OMP_NUM_THREADS"] == "8"              # CPU-spike law intact
