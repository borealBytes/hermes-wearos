"""watch_enable / enablement_patch — line-level config.yaml surgery: idempotent,
comment-preserving, CRLF-safe; the pane's step-2 checklist and `hermes watch enable`
share this exact engine. Also covers the additive watch_status keys (profiles +
apk_release_url) the pane reads, and the CLI arg surface."""

from __future__ import annotations

import argparse
import json

from conftest import load_plugin

module = load_plugin()

REALISTIC = (
    "# Clay's wear profile — persona lives in SOUL.md\n"
    "model: gemini/pro\n"
    "plugins:\n"
    "  enabled:\n"
    "  - agentsdk    # core toolset\n"
    "  entries:\n"
    "    agentsdk:\n"
    "      settings:\n"
    "        board: kanban\n"
    "\n"
    "cron:\n"
    '  schedule: "0 5 * * *"\n'
)


def _patch(text, enable, **kw):
    return module.enablement_patch(text, enable, **kw)


# ── enablement_patch: anchors present ─────────────────────────────────────────
def test_enable_adds_under_existing_anchor_and_survives_idempotency():
    out, changed = _patch(REALISTIC, True)
    assert changed
    assert "\n  - wearos\n" in out                       # item added, PyYAML dash style
    assert "  - agentsdk    # core toolset\n" in out     # sibling + its comment intact
    assert "# Clay's wear profile" in out                # file header untouched
    assert "board: kanban" in out                        # other plugin's settings untouched
    assert 'schedule: "0 5 * * *"' in out                # later top-level sections untouched
    again, changed2 = _patch(out, True)
    assert not changed2 and again == out                 # idempotent: second call is a no-op
    assert module._wearos_enabled(out) and not module._wearos_enabled(REALISTIC)


def test_enable_flow_list_keeps_line_and_siblings():
    text = "plugins:\n  enabled: [agentsdk, browser]  # curated\n"
    out, changed = _patch(text, True)
    assert changed and "enabled: [agentsdk, browser, wearos]  # curated" in out
    assert _patch(out, True)[1] is False
    out2, changed2 = _patch(out, False)
    assert changed2 and "enabled: [agentsdk, browser]  # curated" in out2
    # disable pulls the list item back and leaves the settings block standing (documented)
    assert not module._wearos_enabled(out2)
    assert "gateway_profile: wear" in out2


def test_enable_creates_enabled_key_under_existing_plugins():
    text = "plugins:\n  entries: {}\n"
    out, changed = _patch(text, True)
    assert changed and "  enabled:\n  - wearos" in out
    assert "entries:" in out and "{}" not in out         # empty flow promoted to carry our block
    assert module._wearos_enabled(out)
    assert _patch(out, True)[1] is False


def test_gateway_profile_never_clobbered():
    text = (REALISTIC.replace("    agentsdk:", "    wearos:")
            .replace("        board: kanban", "        gateway_profile: wear-demo"))
    out, changed = _patch(text, True)
    assert changed                                       # only the enabled list was missing
    assert "gateway_profile: wear-demo" in out
    assert "gateway_profile: wear\n" not in out          # no second gateway_profile line
    assert out.count("gateway_profile") == 1


# ── enablement_patch: anchors absent / odd ────────────────────────────────────
def test_missing_plugins_section_appends_whole_block():
    out, changed = _patch("model: x\n", True)
    assert changed and out.endswith(module._new_plugins_block("\n", "wear"))
    assert out.startswith("model: x\n")                  # existing content keeps its place
    assert module._wearos_enabled(out)
    empty, changed_empty = _patch("", True)              # fresh config.yaml
    assert changed_empty and empty.startswith("plugins:") and "\n\n" not in empty


def test_disable_without_anything_to_remove_is_a_noop_both_ways():
    for text in ("model: x\n", "plugins:\n  enabled: [agentsdk]\n",
                 "plugins:\n  enabled:\n  - wearos2\n"):
        out, changed = _patch(text, False)
        assert not changed and out == text
    assert _patch("plugins:\n  enabled: [agentsdk]\n", True)[1] is True  # sanity other way


def test_remove_drops_only_the_wearos_line():
    text = REALISTIC.replace("  - agentsdk    # core toolset\n",
                             "  - agentsdk    # core toolset\n  - wearos\n")
    assert module._wearos_enabled(text)
    out, changed = _patch(text, False)
    assert changed and "  - wearos\n" not in out and "  - agentsdk    # core toolset\n" in out
    assert not module._wearos_enabled(out)
    assert _patch(out, False)[1] is False                # idempotent removal
    # disable leaves the settings block standing (re-enable keeps the user's tuning)
    assert "gateway_profile" not in out


def test_crlf_survives_both_ways_and_lf_stays_lf():
    crlf = REALISTIC.replace("\n", "\r\n")
    assert module._wearos_enabled(crlf) is False
    out, changed = _patch(crlf, True)
    assert changed
    assert "\r\n  - wearos\r\n" in out                   # CRLF preserved, not normalized
    assert all(ln.endswith("\r\n") for ln in out.splitlines(keepends=True))  # every line kept CRLF
    assert _patch(out, True)[1] is False
    off, changed2 = _patch(out, False)
    assert changed2 and "\r\n" in off and module._wearos_enabled(off) is False
    assert "\r\n" not in _patch(REALISTIC, True)[0]      # LF in → LF out


def test_scalar_shapes_refuse_instead_of_dup():
    out, changed = _patch("plugins: legacy\n", True)     # scalar at the key we own
    assert not changed and out == "plugins: legacy\n"    # refuse: appending would duplicate it
    assert _patch("plugins: legacy\n", False) == ("plugins: legacy\n", False)


# ── watch_enable tool: per-profile results against a sandboxed HERMES_HOME ────
def _tree(tmp_path):
    """default (root config) + wear + wear-demo profiles (identity-marker live dirs)."""
    (tmp_path / "config.yaml").write_text("model: x\n", encoding="utf-8")
    wear = tmp_path / "profiles" / "wear"
    wear.mkdir(parents=True)
    (wear / ".env").write_text("API_SERVER_KEY=k\n", encoding="utf-8")
    (wear / "config.yaml").write_text(
        "plugins:\n  enabled:\n  - agentsdk\n", encoding="utf-8")
    demo = tmp_path / "profiles" / "wear-demo"
    demo.mkdir(parents=True)
    (demo / ".env").write_text("API_SERVER_KEY=k\n", encoding="utf-8")
    return {"default": tmp_path / "config.yaml",
            "wear": wear / "config.yaml",
            "wear-demo": demo / "config.yaml"}


def test_watch_enable_named_profiles_reports_before_after(wearos, tmp_path):
    cfgs = _tree(tmp_path)
    (tmp_path / "profiles" / "ghostshell").mkdir()       # no marker → not a profile at all
    res = json.loads(wearos._tool_watch_enable({"agents": ["wear", "wear-demo", "nosuch"]}))
    assert res["enable"] is True
    by = {r["name"]: r for r in res["results"]}
    assert by["wear"] == {"name": "wear", "before": False, "after": True, "changed": True}
    assert by["wear-demo"]["after"] is True and by["wear-demo"]["changed"] is True
    assert by["nosuch"]["error"]                         # absent profile: an error, not a write
    text = cfgs["wear"].read_text(encoding="utf-8")
    assert "  - wearos" in text and "  - agentsdk" in text
    assert "gateway_profile: wear" in text               # default from plugin config (FakeCtx)
    assert (cfgs["wear-demo"]).read_text(encoding="utf-8").startswith("plugins:")


def test_watch_enable_all_covers_default_and_every_profile(wearos, tmp_path):
    _tree(tmp_path)
    res = json.loads(wearos._tool_watch_enable({"agents": "all"}))
    names = [r["name"] for r in res["results"]]
    assert names == ["default", "wear", "wear-demo"]      # default first, then sorted named
    assert all(r["after"] for r in res["results"])
    assert module._wearos_enabled(
        (tmp_path / "config.yaml").read_text(encoding="utf-8"))
    # disable one profile only: the others stay enabled, settings blocks survive
    res2 = json.loads(wearos._tool_watch_enable({"agents": ["wear"], "enable": False}))
    assert res2["enable"] is False and res2["results"][0] == {
        "name": "wear", "before": True, "after": False, "changed": True}
    assert module._wearos_enabled(
        (tmp_path / "profiles" / "wear" / "config.yaml").read_text(encoding="utf-8")) is False


def test_watch_status_gains_profiles_and_release_url_without_losing_shape(wearos, tmp_path, monkeypatch):
    _tree(tmp_path)
    monkeypatch.setattr(wearos, "tailnet_state", lambda: {"tailscaled": {"ok": True, "detail": "fake"}})
    status = json.loads(wearos._tool_watch_status({}))
    assert set(status) >= {"checks", "devices", "profiles", "apk_release_url"}
    assert isinstance(status["checks"], dict) and isinstance(status["devices"], dict)
    assert status["apk_release_url"] == module.RELEASES_LATEST
    assert [(p["name"], p["enabled"]) for p in status["profiles"]] == [
        ("default", False), ("wear", False), ("wear-demo", False)]
    wearos._tool_watch_enable({"agents": ["wear"]})
    status2 = json.loads(wearos._tool_watch_status({}))
    assert [p for p in status2["profiles"] if p["name"] == "wear"][0]["enabled"] is True


# ── `hermes watch enable` CLI surface ─────────────────────────────────────────
def _cli_parser():
    root = argparse.ArgumentParser(prog="hermes")
    watch = root.add_subparsers(dest="command").add_parser("watch")
    module._watch_cli_setup(watch)          # the host passes the `watch` parser itself
    return root


def test_cli_enable_args_parse_defaults_and_disable():
    ns = _cli_parser().parse_args(["watch", "enable"])
    assert ns.watch_command == "enable" and ns.agents == "all" and ns.disable is False
    ns = _cli_parser().parse_args(["watch", "enable", "--agents", "wear,wear-demo", "--disable"])
    assert ns.agents == "wear,wear-demo" and ns.disable is True


def test_cli_enable_runs_through_the_same_engine(wearos, tmp_path, capsys):
    _tree(tmp_path)
    ns = _cli_parser().parse_args(["watch", "enable", "--agents", "wear,ghostshell"])
    wearos._watch_cli_run(ns)
    out = capsys.readouterr().out
    assert "✓ enabled · wear" in out
    assert "✗ ghostshell" in out                          # marker-less dir → real error line
    assert "2 profile(s) inspected, 1 changed" in out
    assert module._wearos_enabled(
        (tmp_path / "profiles" / "wear" / "config.yaml").read_text(encoding="utf-8"))
    ns = _cli_parser().parse_args(["watch", "enable", "--agents", "wear", "--disable"])
    wearos._watch_cli_run(ns)
    assert "− disabled · wear" in capsys.readouterr().out
