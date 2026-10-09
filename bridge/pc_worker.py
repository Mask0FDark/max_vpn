"""PC-side request processor for an explicitly authorized MAX test chat.

Only bounded one-request TCP exchanges; not a VPN, NAT or streaming transport.
Playwright sync sessions must remain on one thread, so network I/O runs in a
separate thread when serving a real browser session.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import os
import secrets
import time

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305, AESGCM
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from bridge.max_debug import extract_frame
from bridge.max_rpc import MaxMessageTransport
from bridge.tcp_exchange import execute_request


def wrap(secret: bytes, request_id: str, data: bytes, *, algorithm: str = "chacha") -> bytes:
    """Encrypt and authenticate one message; bind it to the request id."""
    if len(secret) < 32:
        raise ValueError("shared secret must be at least 32 bytes")
    nonce = secrets.token_bytes(12)
    if algorithm not in ("chacha", "aes"):
        raise ValueError("unsupported authenticated encryption")
    cipher = (AESGCM if algorithm == "aes" else ChaCha20Poly1305)(hashlib.sha256(secret).digest())
    header = b"MX2" if algorithm == "aes" else b"MX1"
    return header + nonce + cipher.encrypt(nonce, data, request_id.encode("ascii"))


def unwrap(secret: bytes, request_id: str, wrapped: bytes) -> bytes:
    if len(secret) < 32 or len(wrapped) < 31 or wrapped[:3] not in (b"MX1", b"MX2"):
        raise ValueError("invalid encrypted message")
    nonce, ciphertext = wrapped[3:15], wrapped[15:]
    cipher = (AESGCM if wrapped[:3] == b"MX2" else ChaCha20Poly1305)(hashlib.sha256(secret).digest())
    try:
        return cipher.decrypt(nonce, ciphertext, request_id.encode("ascii"))
    except InvalidTag as exc:
        raise ValueError("invalid message authenticator") from exc


class PCWorker:
    def __init__(self, transport: MaxMessageTransport, secret: bytes):
        if len(secret) < 32:
            raise ValueError("shared secret must be at least 32 bytes")
        self.transport = transport
        self.secret = secret
        self.handled: set[str] = set()
        self.reply_algorithms: dict[str, str] = {}

    def _pending(self) -> list[tuple[str, bytes]]:
        pending = []
        for message in self.transport.receive():
            if message["kind"] != "request":
                continue
            rid = message["request_id"]
            if rid in self.handled:
                continue
            try:
                payload = unwrap(self.secret, rid, message["payload"])
            except ValueError:
                continue
            self.handled.add(rid)
            self.reply_algorithms[rid] = "aes" if message["payload"].startswith(b"MX2") else "chacha"
            pending.append((rid, payload))
        return pending

    def _send_result(self, rid: str, reply: bytes, failed: bool = False) -> None:
        self.transport.send("error" if failed else "response",
                            wrap(self.secret, rid, reply,
                                 algorithm=self.reply_algorithms.pop(rid, "chacha")), rid)

    async def process(self) -> int:
        """Async form for in-memory unit tests, without a sync Playwright page."""
        count = 0
        for rid, payload in self._pending():
            try:
                reply = await execute_request(payload)
                self._send_result(rid, reply)
            except RuntimeError:
                self._send_result(rid, b"request failed", failed=True)
            count += 1
        return count

    def process_sync(self) -> int:
        """Real Playwright-compatible loop; all browser I/O remains on this thread."""
        count = 0
        for rid, payload in self._pending():
            try:
                with ThreadPoolExecutor(max_workers=1) as pool:
                    reply = pool.submit(asyncio.run, execute_request(payload)).result(timeout=15)
                self._send_result(rid, reply)
            except (RuntimeError, TimeoutError):
                self._send_result(rid, b"request failed", failed=True)
            count += 1
        return count


def run(automation_root: Path, *, poll_seconds: float = 2.0) -> None:
    from max_automation import AccountRegistry, MaxConfig, MaxWeb

    secret = os.environ.get("MAX_VPN_SHARED_SECRET", "").encode("utf-8")
    if len(secret) < 32:
        raise ValueError("set MAX_VPN_SHARED_SECRET to a private random string of at least 32 bytes")
    os.environ.setdefault("NODE_OPTIONS", "--max-old-space-size=1024")
    config = MaxConfig.load(automation_root / "config" / "chats.json")
    config.require("debug", "read_debug")
    config.require("debug", "send_debug")
    accounts = AccountRegistry.load(automation_root / "config" / "accounts.json", automation_root)
    with MaxWeb(config, accounts.get("technical"), accounts) as web:
        transport = MaxMessageTransport(web, role="host")
        # Ignore visible old frames; do not repeat previous TCP requests on restart.
        transport.seen_frames.update(
            frame for message in web.read_debug_messages(100)
            if (frame := extract_frame(message))
        )
        worker = PCWorker(transport, secret)
        print("MAX_PC_WORKER_READY", flush=True)
        while True:
            worker.process_sync()
            time.sleep(poll_seconds)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--automation-root", type=Path, required=True)
    parser.add_argument("--poll-seconds", type=float, default=2.0)
    args = parser.parse_args()
    run(args.automation_root, poll_seconds=max(0.5, args.poll_seconds))
