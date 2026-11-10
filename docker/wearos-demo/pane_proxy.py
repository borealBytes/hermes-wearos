#!/usr/bin/env python3
"""Tiny stdlib reverse proxy: injects the dashboard bearer token AND serves the
demo pane mock statically, so the headless-capture chromium session is fully
same-origin and authenticated without a cookie dance.
Listens on 127.0.0.1:9188 -> 127.0.0.1:9119. /mock/<file> serves /opt/mock-pane."""
import http.server, urllib.request, urllib.error, sys, threading
from pathlib import Path

TOKEN = sys.argv[1] if len(sys.argv) > 1 else "/run/secrets/session_token"
MOCK_DIR = Path(sys.argv[2] if len(sys.argv) > 2 else "/opt/mock-pane")
TYPES = {".html": "text/html", ".js": "text/javascript", ".css": "text/css",
         ".json": "application/json", ".png": "image/png", ".wav": "audio/wav"}

class H(http.server.BaseHTTPRequestHandler):
    def _send(self, data, ctype, code=200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _proxy(self, body):
        if self.path.startswith("/mock/"):
            name = self.path[len("/mock/"):].split("?")[0] or "pane.html"
            f = (MOCK_DIR / name).resolve()
            if f.is_file() and MOCK_DIR in f.parents:
                return self._send(f.read_bytes(),
                                  TYPES.get(f.suffix, "application/octet-stream"))
            return self._send(b"not found", "text/plain", 404)
        req = urllib.request.Request("http://127.0.0.1:9119" + self.path,
                                     data=body, method=self.command)
        for h in ("Content-Type", "Accept", "Referer", "User-Agent"):
            if self.headers.get(h):
                req.add_header(h, self.headers[h])
        req.add_header("Authorization", "Bearer " + TOKEN)
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                self._send(r.read(), r.headers.get("Content-Type", "application/octet-stream"), r.status)
        except urllib.error.HTTPError as e:
            self._send(e.read(), e.headers.get("Content-Type", "text/plain"), e.code)

    def do_GET(self):
        self._proxy(None)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        self._proxy(self.rfile.read(n) if n else b"")

    def log_message(self, *a):
        pass

if __name__ == "__main__":
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 9188), H)
    srv.serve_forever()
