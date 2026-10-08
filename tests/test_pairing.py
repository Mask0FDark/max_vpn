import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
from server.pairing import issue_code, redeem, _attempts
from server.public import app


class PairingTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = Path(self.dir.name) / "pairing.json"
        self.env = patch.dict(os.environ, {
            "MAXVPN_RELAY_TOKEN": "t" * 64,
            "MAXVPN_PAIRING_FILE": str(self.path),
        })
        self.env.start()
        _attempts.clear()
        self.client = TestClient(app)

    def tearDown(self):
        self.client.close()
        self.env.stop()
        self.dir.cleanup()

    def test_one_time_pairing_token(self):
        code = issue_code(self.path)
        self.assertEqual(len(code), 24)
        denied = self.client.post("/api/pair", json={"code": "0" * 24})
        self.assertEqual(denied.status_code, 403)
        response = self.client.post("/api/pair", json={"code": code})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["token"], "t" * 64)
        self.assertEqual(response.json()["mode"], "direct_vps_https")
        self.assertFalse(self.path.exists())
        self.assertEqual(self.client.post("/api/pair", json={"code": code}).status_code, 403)
        self.assertEqual(response.headers["Cache-Control"], "no-store")

    def test_expired_pair_code(self):
        code = issue_code(self.path)
        self.assertIsNone(redeem(code, self.path, now=time.time() + 90000))
        self.assertFalse(self.path.exists())

    def test_attempt_limit(self):
        issue_code(self.path)
        codes = ["1" * 24] * 10
        results = [self.client.post("/api/pair", json={"code": code}).status_code for code in codes]
        self.assertEqual(results[:8], [403] * 8)
        self.assertEqual(results[8:], [429, 429])

    def test_no_key_configured(self):
        code = issue_code(self.path)
        with patch.dict(os.environ, {"MAXVPN_RELAY_TOKEN": ""}):
            self.assertIsNone(redeem(code, self.path))


if __name__ == "__main__":
    unittest.main()
