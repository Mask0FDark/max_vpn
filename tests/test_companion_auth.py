from __future__ import annotations

import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from companion.auth_flow import classify_login_page, write_state, open_edge_without_driver, authorize
from companion.server import AuthController, create_server


class AuthFlowTests(unittest.TestCase):
    def test_classify_login(self):
        self.assertEqual(classify_login_page(True, False), "signed_in")
        self.assertEqual(classify_login_page(False, True), "awaiting_sign_in")
        self.assertEqual(classify_login_page(False, False), "checking")

    def test_direct_edge_fallback_does_not_collect_credentials(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with patch("companion.auth_flow.subprocess.Popen") as opened:
                self.assertEqual(open_edge_without_driver(
                    Path("edge.exe"), root / "profile", root / "auth-status.json"), 0)
                args = opened.call_args.args[0]
                self.assertEqual(args[-1], "https://web.max.ru/")
                self.assertTrue(any("--user-data-dir=" in arg for arg in args))
                state = json.loads((root / "auth-status.json").read_text(encoding="utf-8"))
                self.assertEqual(state["state"], "manual_browser_opened")
                self.assertNotIn("cookies", state)

    def test_persist_status_without_credentials(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "auth-status.json"
            write_state(target, "awaiting_sign_in")
            data = json.loads(target.read_text(encoding="utf-8"))
            self.assertEqual(data["state"], "awaiting_sign_in")
            self.assertNotIn("cookies", data)
            self.assertNotIn("password", data)


class CompanionHTTPTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.controller = AuthController(Path(self.temp.name))
        self.server = create_server(0, self.controller)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.temp.cleanup()

    def fetch(self, route, method="GET", headers=None):
        request = Request(self.url + route, method=method, headers=headers or {})
        try:
            with urlopen(request, timeout=4) as response:
                return response.status, response.read()
        except HTTPError as error:
            try:
                return error.code, error.read()
            finally:
                error.close()

    def test_serves_local_page_and_status(self):
        code, body = self.fetch("/")
        self.assertEqual(code, 200)
        self.assertIn(b"maxLoginButton", body)
        code, body = self.fetch("/api/auth/status")
        self.assertEqual(code, 200)
        payload = json.loads(body)
        self.assertEqual(payload["state"], "not_started")
        self.assertEqual(payload["csrf"], self.controller.token)
        code, body = self.fetch("/app.js")
        self.assertEqual(code, 200)
        self.assertIn(b"/api/auth/start", body)

    def test_csrf_and_origin_required(self):
        self.controller.start = Mock(return_value=(True, "opening"))
        good = {"Origin": self.url, "X-MAXVPN-CSRF": self.controller.token}
        for bad in ({}, {"Origin": "https://attacker.example",
                        "X-MAXVPN-CSRF": self.controller.token},
                    {"Origin": self.url, "X-MAXVPN-CSRF": "wrong"}):
            code, _ = self.fetch("/api/auth/start", "POST", bad)
            self.assertEqual(code, 403)
        self.controller.start.assert_not_called()
        code, body = self.fetch("/api/auth/start", "POST", good)
        self.assertEqual(code, 202)
        self.assertEqual(json.loads(body)["state"], "opening")
        self.controller.start.assert_called_once_with()

    def test_no_second_listener_on_same_port(self):
        with self.assertRaises(OSError):
            duplicate = create_server(self.server.server_port)
            duplicate.server_close()

    def test_rejects_nonlocal_host(self):
        code, _ = self.fetch("/api/auth/status", headers={"Host": "evil.example"})
        self.assertEqual(code, 403)

    def test_auth_launch_uses_isolated_profile(self):
        with patch("companion.server.subprocess.Popen") as launched:
            launched.return_value.poll.return_value = None
            ok, state = self.controller.start()
            self.assertTrue(ok)
            self.assertEqual(state, "opening")
            command = launched.call_args.args[0]
            self.assertIn("companion.auth_flow", command)
            self.assertIn(str(self.controller.root), command)
            self.assertEqual(self.controller.start(), (False, "already_running"))


if __name__ == "__main__":
    unittest.main()
