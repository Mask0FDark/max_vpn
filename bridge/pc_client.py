"""Diagnostic PC client for a separate authenticated MAX account.

This is one bounded TCP request/response over MAX messages, not a VPN client.
The remote PC worker must already be running with the same private shared key.
"""
from __future__ import annotations

import argparse
import os
import time
import uuid
from pathlib import Path

from bridge.max_rpc import MaxMessageTransport
from bridge.pc_worker import unwrap, wrap
from bridge.tcp_exchange import request_payload


class PCClient:
    def __init__(self, transport: MaxMessageTransport, secret: bytes):
        if len(secret) < 32:
            raise ValueError("shared secret must be at least 32 bytes")
        self.transport = transport
        self.secret = secret

    def exchange(self, host: str, port: int, data: bytes, *, timeout: float = 60) -> bytes:
        rid = uuid.uuid4().hex
        request = request_payload(host, port, data)
        self.transport.send("request", wrap(self.secret, rid, request), rid)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for message in self.transport.receive():
                if message["request_id"] != rid:
                    continue
                if message["kind"] not in ("response", "error"):
                    continue
                result = unwrap(self.secret, rid, message["payload"])
                if message["kind"] == "error":
                    raise RuntimeError(result.decode("utf-8", errors="replace"))
                return result
            time.sleep(2)
        raise TimeoutError("MAX PC worker did not reply")


def run(automation_root: Path, account_key: str, host: str, port: int, data: bytes) -> None:
    from max_automation import AccountRegistry, MaxConfig, MaxWeb

    secret = os.environ.get("MAX_VPN_SHARED_SECRET", "").encode("utf-8")
    if len(secret) < 32:
        raise ValueError("MAX_VPN_SHARED_SECRET must be at least 32 bytes")
    os.environ.setdefault("NODE_OPTIONS", "--max-old-space-size=1024")
    config = MaxConfig.load(automation_root / "config" / "chats.json")
    config.require("debug", "read_debug")
    config.require("debug", "send_debug")
    accounts = AccountRegistry.load(automation_root / "config" / "accounts.json", automation_root)
    with MaxWeb(config, accounts.get(account_key), accounts) as web:
        reply = PCClient(MaxMessageTransport(web), secret).exchange(host, port, data)
    print("MAX_PC_RESPONSE_BYTES", len(reply))
    print(reply.decode("utf-8", errors="replace"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Single TCP request through the MAX PC worker")
    parser.add_argument("--automation-root", type=Path, required=True)
    parser.add_argument("--account", default="technical")
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--text", required=True)
    args = parser.parse_args()
    run(args.automation_root, args.account, args.host, args.port, args.text.encode("utf-8"))
