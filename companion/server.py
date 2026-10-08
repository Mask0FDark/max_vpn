"""Self-hosted MAX VPN sign-in companion, listening ONLY on localhost.

Unlike a public website, this local process can open a real official MAX Web
login window and save the user's Edge profile on their own PC.
"""
from __future__ import annotations

import argparse
import json
import os
import secrets
import subprocess
import sys
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from companion.auth_flow import state_root, write_state

class LocalOnlyHTTPServer(ThreadingHTTPServer):
    # On Windows SO_REUSEADDR can permit two processes to bind the same port.
    # Never allow a stale companion process to share this security-sensitive port.
    allow_reuse_address = False


SITE_DIR = Path(__file__).resolve().parents[1] / "site"
PROJECT_DIR = SITE_DIR.parent
ASSETS = {"/": ("index.html", "text/html; charset=utf-8"),
          "/index.html": ("index.html", "text/html; charset=utf-8"),
          "/style.css": ("style.css", "text/css; charset=utf-8"),
          "/app.js": ("app.js", "text/javascript; charset=utf-8")}


class AuthController:
    def __init__(self, root: Path | None = None):
        self.root = root or state_root()
        self.token = secrets.token_urlsafe(32)
        self._lock = threading.Lock()
        self.process: subprocess.Popen | None = None

    def status(self) -> dict:
        status_path = self.root / "auth-status.json"
        try:
            payload = json.loads(status_path.read_text(encoding="utf-8"))
        except (FileNotFoundError, ValueError, OSError):
            payload = {"state": "not_started"}
        state = payload.get("state", "not_started")
        if state not in {"not_started", "opening", "awaiting_sign_in", "checking",
                         "signed_in", "browser_closed", "manual_browser_opened", "error"}:
            state = "error"
        return {"state": state,
                "running": self.process is not None and self.process.poll() is None,
                "previously_signed_in": bool(payload.get("previously_signed_in", False)),
                "error": payload.get("error", None),
                "phase": payload.get("phase", None),
                "csrf": self.token}

    def start(self) -> tuple[bool, str]:
        with self._lock:
            if self.process is not None and self.process.poll() is None:
                return False, "already_running"
            self.root.mkdir(parents=True, exist_ok=True)
            write_state(self.root / "auth-status.json", "opening")
            command = [sys.executable, "-m", "companion.auth_flow",
                       "--state-dir", str(self.root)]
            environment = dict(os.environ)
            environment.setdefault("NODE_OPTIONS", "--max-old-space-size=1024")
            try:
                self.process = subprocess.Popen(command, cwd=PROJECT_DIR,
                                                env=environment, stdin=subprocess.DEVNULL,
                                                stdout=subprocess.DEVNULL,
                                                stderr=subprocess.DEVNULL)
            except OSError:
                write_state(self.root / "auth-status.json", "error", error="browser_start_failed")
                return False, "browser_start_failed"
            return True, "opening"


def create_server(port: int = 18763, controller: AuthController | None = None) -> ThreadingHTTPServer:
    controller = controller or AuthController()
    allowed_hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}

    class Handler(BaseHTTPRequestHandler):
        def send_bytes(self, body: bytes, code: int, content_type: str):
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Content-Security-Policy",
                             "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'")
            self.end_headers()
            self.wfile.write(body)

        def json_response(self, body: dict, code: int = 200):
            self.send_bytes(json.dumps(body, ensure_ascii=False).encode("utf-8"),
                            code, "application/json; charset=utf-8")

        def allowed(self) -> bool:
            # DNS rebinding protection: only direct localhost Host headers.
            return self.headers.get("Host", "").lower() in allowed_hosts

        def do_GET(self):
            if not self.allowed():
                return self.json_response({"error": "bad_host"}, 403)
            pathname = urlsplit(self.path).path
            if pathname == "/api/auth/status":
                return self.json_response(controller.status())
            if pathname in ASSETS:
                filename, media_type = ASSETS[pathname]
                return self.send_bytes((SITE_DIR / filename).read_bytes(), 200, media_type)
            self.json_response({"error": "not_found"}, 404)

        def do_POST(self):
            if not self.allowed():
                return self.json_response({"error": "bad_host"}, 403)
            if urlsplit(self.path).path != "/api/auth/start":
                return self.json_response({"error": "not_found"}, 404)
            origin = self.headers.get("Origin", "")
            if origin not in {f"http://{host}" for host in allowed_hosts}:
                return self.json_response({"error": "invalid_origin"}, 403)
            if self.headers.get("X-MAXVPN-CSRF") != controller.token:
                return self.json_response({"error": "invalid_csrf"}, 403)
            if self.headers.get("Content-Length", "0") not in ("0", ""):
                return self.json_response({"error": "unexpected_body"}, 400)
            created, state = controller.start()
            self.json_response({"state": state}, 202 if created else 409)

        def log_message(self, format: str, *args):
            # Requests must not expose authentication state data in public logs.
            return

    server = LocalOnlyHTTPServer(("127.0.0.1", port), Handler)
    allowed_hosts.clear()
    allowed_hosts.update({f"127.0.0.1:{server.server_port}", f"localhost:{server.server_port}"})
    server.daemon_threads = True
    return server


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18763)
    parser.add_argument("--open", action="store_true")
    args = parser.parse_args()
    server = create_server(args.port)
    url = f"http://127.0.0.1:{server.server_port}/"
    print("MAX_VPN_COMPANION_READY", url, flush=True)
    if args.open:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
