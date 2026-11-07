"""Media handlers — mermaid→PNG, /img allow-list, /media pre-shrink (handoff §4).

PORT IN PROGRESS: the in-process replacements for server/watchkit/watchkit.mjs land here
(mermaid render, image scaling to the round buffer, media sniffing/Opus pre-shrink).
Until each handler is ported, the Node sidecar (scripts/watchkit.sh) stays the DOCUMENTED
FALLBACK — the watchkit endpoints degrade to pass-through, never to a broken watch.

Cut line (handoff §4): if the deadline bites, this file shrinks and the sidecar stays;
the pairing happy path and watch_send never do.
"""

from __future__ import annotations


def render_mermaid(source: str) -> bytes:
    """Mermaid → PNG in-process. TODO(media-port): port renderMermaid from
    watchkit.mjs keeping its renderer cache; returns PNG bytes for the watch /img lane."""
    raise NotImplementedError("media lane still riding the watchkit Node sidecar (documented fallback)")
