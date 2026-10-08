import os
import socketserver
import threading
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from server.public import app
from server.relay import resolve_public


class Echo(socketserver.BaseRequestHandler):
    def handle(self):
        while True:
            data = self.request.recv(2048)
            if not data:
                return
            self.request.sendall(data)


class RelayTests(unittest.TestCase):
    def test_no_token_rejected(self):
        with patch.dict(os.environ, {"MAXVPN_RELAY_TOKEN": "x" * 50}):
            with TestClient(app) as client:
                with self.assertRaises(WebSocketDisconnect) as bad:
                    with client.websocket_connect("/relay/tcp?host=example.com&port=443"):
                        pass
                self.assertEqual(bad.exception.code, 1008)

    def test_no_internal_ip(self):
        import asyncio
        import socket
        with self.assertRaises(ValueError):
            asyncio.run(resolve_public("127.0.0.1", 22, socktype=socket.SOCK_STREAM))

    def test_authenticated_byte_stream(self):
        server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Echo)
        server.daemon_threads = True
        t = threading.Thread(target=server.serve_forever, daemon=True)
        t.start()

        async def fake_resolve(host, port, socktype):
            self.assertEqual(host, "example.com")
            return (2, ("127.0.0.1", server.server_address[1]))
        try:
            with patch.dict(os.environ, {"MAXVPN_RELAY_TOKEN": "a" * 50}):
                with patch("server.relay.resolve_public", side_effect=fake_resolve):
                    with TestClient(app) as client:
                        headers = {"X-MAXVPN-Token": "a" * 50}
                        with client.websocket_connect("/relay/tcp?host=example.com&port=443", headers=headers) as ws:
                            ws.send_bytes(b"hello encrypted websocket")
                            self.assertEqual(ws.receive_bytes(), b"hello encrypted websocket")
        finally:
            server.shutdown()
            server.server_close()
            t.join(3)


if __name__ == "__main__":
    unittest.main()
