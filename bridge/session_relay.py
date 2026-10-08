"""MAX message adapter for persistent TCP sessions.

One account receives routed mobile->host requests, and host->mobile replies.
Browser automation remains on its own thread; sockets stay on SessionRunner.
"""
from __future__ import annotations

import os
import time

from bridge.max_rpc import MaxMessageTransport
from bridge.pc_worker import PCWorker, wrap, unwrap
from bridge.tcp_sessions import SessionRunner, encode_command, decode_result


class SessionPCWorker(PCWorker):
    def __init__(self, transport: MaxMessageTransport, secret: bytes,
                 runner: SessionRunner | None = None):
        super().__init__(transport, secret)
        self.runner = runner or SessionRunner()
        self.owns_runner = runner is None

    def process_sync(self) -> int:
        count = 0
        for rid, request in self._pending():
            try:
                reply = self.runner.execute(request)
                self._send_result(rid, reply)
            except RuntimeError:
                self._send_result(rid, b"session operation failed", failed=True)
            count += 1
        return count

    def close(self) -> None:
        if self.owns_runner:
            self.runner.stop()


class SessionClient:
    """A synchronous request client. Do not share with concurrent consumers."""

    def __init__(self, transport: MaxMessageTransport, secret: bytes):
        if len(secret) < 32:
            raise ValueError("shared secret is too short")
        self.transport = transport
        self.secret = secret

    def _request(self, data: bytes, timeout: float = 90) -> dict:
        import uuid
        rid = uuid.uuid4().hex
        self.transport.send("request", wrap(self.secret, rid, data), rid)
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for message in self.transport.receive():
                if message["request_id"] != rid:
                    continue
                if message["kind"] not in ("response", "error"):
                    continue
                response = unwrap(self.secret, rid, message["payload"])
                if message["kind"] == "error":
                    raise RuntimeError(response.decode("utf-8", errors="replace"))
                return decode_result(response)
            time.sleep(1.5)
        raise TimeoutError("MAX session reply timeout")

    def open(self, host: str, port: int) -> str:
        result = self._request(encode_command("open", host=host, port=port))
        return result["session"]

    def write(self, session: str, data: bytes) -> None:
        self._request(encode_command("write", session=session, data=data))

    def read(self, session: str) -> tuple[str, bytes]:
        response = self._request(encode_command("read", session=session))
        return response["status"], response.get("data", b"")

    def close(self, session: str) -> None:
        self._request(encode_command("close", session=session))
