import socket
import socketserver
import threading
import time
import unittest

from bridge.connect_over_session import LocalConnectServer
from bridge.max_rpc import MaxMessageTransport
from bridge.session_relay import SessionClient, SessionPCWorker
from bridge.tcp_sessions import SessionManager, SessionRunner

KEY = b"dedicated-testing-secret-at-least-32-bytes"

class Echo(socketserver.BaseRequestHandler):
    def handle(self):
        while True:
            data = self.request.recv(8192)
            if not data:
                break
            self.request.sendall(data)

class Chat:
    def __init__(self):
        self.messages = []
        self.lock = threading.Lock()

    def send_text(self, key, action, text):
        with self.lock:
            self.messages.append(text)

    def read_debug_messages(self, limit=100):
        with self.lock:
            return self.messages[-limit:]


class ConnectAdapterTests(unittest.TestCase):
    def test_http_connect_through_encrypted_same_account_sessions(self):
        echo = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Echo)
        echo.daemon_threads = True
        echo_thread = threading.Thread(target=echo.serve_forever, daemon=True)
        echo_thread.start()

        chat = Chat()
        runner = SessionRunner(SessionManager(allow_private=True))
        host = SessionPCWorker(MaxMessageTransport(chat, role="host"), KEY, runner)
        phone = SessionClient(MaxMessageTransport(chat, role="mobile"), KEY)
        proxy = LocalConnectServer(phone, port=0, idle_timeout=15)
        proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True)
        proxy_thread.start()
        stop = threading.Event()
        failures = []
        def serve_host():
            try:
                while not stop.wait(0.02):
                    host.process_sync()
            except Exception as exc:
                failures.append(exc)
        host_thread = threading.Thread(target=serve_host, daemon=True)
        host_thread.start()
        try:
            with socket.create_connection(proxy.server_address, timeout=25) as client:
                client.settimeout(30)
                port = echo.server_address[1]
                client.sendall(f"CONNECT 127.0.0.1:{port} HTTP/1.1\r\nHost: test\r\n\r\n".encode())
                header = b""
                while b"\r\n\r\n" not in header:
                    header += client.recv(512)
                self.assertIn(b"200 Connection Established", header)
                for chunk in (b"hello", b"world"):
                    client.sendall(chunk)
                    reply = client.recv(512)
                    self.assertEqual(reply, chunk)
            self.assertFalse(failures)
        finally:
            proxy.shutdown()
            proxy.server_close()
            proxy_thread.join(3)
            stop.set()
            host_thread.join(3)
            runner.stop()
            echo.shutdown()
            echo.server_close()
            echo_thread.join(3)


if __name__ == "__main__":
    unittest.main()
