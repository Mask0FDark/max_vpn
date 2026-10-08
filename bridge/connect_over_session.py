"""Loopback-only HTTP CONNECT adapter backed by persistent MAX TCP sessions.

Experimental local proxy. Each CONNECT is routed through the encrypted MAX
message transport and its host worker. It is neither an Android VpnService nor
a general packet tunnel. MAX's message latency may make real TLS unusably slow.
"""
from __future__ import annotations

import argparse
import os
import socket
import socketserver
import time

from bridge.pc_proxy import parse_connect
from bridge.session_relay import SessionClient

HEADER_LIMIT = 16384


class ConnectHandler(socketserver.BaseRequestHandler):
    def handle(self):
        remote_id = None
        sock = self.request
        sock.settimeout(10)
        try:
            header = b""
            while b"\r\n\r\n" not in header:
                data = sock.recv(1)
                if not data:
                    return
                header += data
                if len(header) > HEADER_LIMIT:
                    raise ValueError("CONNECT header too large")
            host, port = parse_connect(header)
            remote_id = self.server.session_client.open(host, port)
        except (ValueError, OSError, TimeoutError, RuntimeError):
            self._send_error(b"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
            return
        try:
            sock.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            sock.settimeout(0.1)
            last_activity = time.monotonic()
            while time.monotonic() - last_activity < self.server.idle_timeout:
                try:
                    outgoing = sock.recv(2048)
                    if not outgoing:
                        break
                    self.server.session_client.write(remote_id, outgoing)
                    last_activity = time.monotonic()
                except socket.timeout:
                    pass
                try:
                    state, incoming = self.server.session_client.read(remote_id)
                except (RuntimeError, TimeoutError, ValueError):
                    break
                if incoming:
                    sock.sendall(incoming)
                    last_activity = time.monotonic()
                if state == "eof":
                    break
        except (OSError, RuntimeError, TimeoutError):
            pass
        finally:
            try:
                self.server.session_client.close(remote_id)
            except (OSError, RuntimeError, TimeoutError, ValueError):
                pass

    def _send_error(self, data: bytes):
        try:
            self.request.sendall(data)
        except OSError:
            pass


class LocalConnectServer(socketserver.TCPServer):
    allow_reuse_address = False
    daemon_threads = True
    def __init__(self, session_client: SessionClient, port: int = 18765,
                 idle_timeout: float = 60):
        if idle_timeout <= 0:
            raise ValueError("idle_timeout must be positive")
        self.session_client = session_client
        self.idle_timeout = idle_timeout
        super().__init__(("127.0.0.1", port), ConnectHandler)


def main() -> None:
    parser = argparse.ArgumentParser(description="Experimental MAX-backed localhost CONNECT proxy")
    parser.add_argument("--chat-id", required=True, help="Authorized dedicated diagnostic MAX chat")
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args()

    from companion.max_chat import CompanionMaxChat
    from bridge.max_rpc import MaxMessageTransport
    secret = os.environ.get("MAX_VPN_SHARED_SECRET", "").encode("utf-8")
    if len(secret) < 32:
        raise ValueError("MAX_VPN_SHARED_SECRET is required (at least 32 bytes)")
    with CompanionMaxChat(args.chat_id) as browser:
        channel = MaxMessageTransport(browser, role="mobile")
        client = SessionClient(channel, secret)
        with LocalConnectServer(client, args.port) as server:
            print(f"MAX_CONNECT_CLIENT_READY=127.0.0.1:{server.server_address[1]}", flush=True)
            server.serve_forever()


if __name__ == "__main__":
    main()
