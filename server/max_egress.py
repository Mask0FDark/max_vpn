"""Opt-in MAX message egress on a user's own VPS.

Uses an independently authorized PyMax WebClient (QR confirmation in the
official MAX app). Does not accept MAX SMS/2FA passwords or export cookies.
Requires a dedicated, explicitly configured chat ID and private shared key.
No VPS data traffic reaches this process until a user approves MAX login.
"""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import os
import re
import stat
import time
from pathlib import Path
from typing import Any

from bridge.max_debug import extract_frame
from bridge.max_rpc import MaxMessageTransport
from bridge.session_relay import SessionPCWorker


class MaxQrForOperator:
    async def show_qr(self, qr_url: str) -> None:
        if not qr_url.startswith(("https://", "max://")) or len(qr_url) > 2048:
            raise ValueError("unexpected MAX authorization link")
        # The QR/link is only shown in the owner's terminal, never via
        # the public website, database, access logs, or GitHub.
        print("MAX_AUTHORIZATION_LINK_FOR_OPERATOR_ONLY=" + qr_url, flush=True)


class RejectWebPassword:
    async def get_password(self, hint: str | None = None) -> str:
        # Do not collect an account 2FA password in a third-party service.
        raise RuntimeError("2FA authorization requires the official MAX app")


class PyMaxHistoryChat:
    """Adapter with the synchronous methods expected by MaxMessageTransport.

    Work is delegated to the PyMax owner's asyncio loop, so a worker thread
    cannot race WebClient's socket and persistent session store.
    """

    def __init__(self, client: Any, chat_id: int, loop: asyncio.AbstractEventLoop):
        if type(chat_id) is not int or chat_id == 0 or abs(chat_id) > (2**63 - 1):
            raise ValueError("dedicated MAX chat ID must be a signed nonzero integer")
        self.client = client
        self.chat_id = chat_id
        self.loop = loop

    def _wait(self, coroutine: Any, timeout: int = 45) -> Any:
        future = asyncio.run_coroutine_threadsafe(coroutine, self.loop)
        try:
            return future.result(timeout=timeout)
        except Exception:
            future.cancel()
            raise

    def send_text(self, chat_key: str, action: str, text: str) -> None:
        if (chat_key, action) != ("debug", "send_debug"):
            raise ValueError("worker can only use its configured MAX test chat")
        if not text.startswith("M0FD-TUNNEL-V1:") or len(text) > 4000:
            raise ValueError("invalid encrypted frame")
        self._wait(self.client.send_message(chat_id=self.chat_id, text=text))

    def read_debug_messages(self, limit: int = 100) -> list[str]:
        if not 1 <= limit <= 100:
            raise ValueError("history read limit exceeded")
        messages = self._wait(self.client.fetch_history(
            chat_id=self.chat_id, backward=limit, get_messages=True))
        result = []
        for message in messages:
            if message.chat_id == self.chat_id and isinstance(message.text, str):
                # MaxMessageTransport verifies encrypted envelopes independently.
                result.append(message.text)
        return result


async def run(chat_id: int, *, session_dir: Path, secret: bytes,
              poll_seconds: float = 3.0) -> None:
    if len(secret) < 32:
        raise ValueError("MAX_VPN_SHARED_SECRET must be a random 32+-byte key")
    if not 1 <= poll_seconds <= 30:
        raise ValueError("invalid MAX polling interval")
    session_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    if os.name == "posix":
        session_dir.chmod(0o700)

    # Lazy import lets tests run without loading untrusted third-party SDK.
    from pymax import WebClient, QrAuthFlow
    from bridge.tcp_sessions import SessionRunner

    client = WebClient(
        work_dir=str(session_dir), session_name="max-web.db",
        auth_flow=QrAuthFlow(MaxQrForOperator(), password_provider=RejectWebPassword()),
    )
    await client.connect()
    runner = SessionRunner()
    try:
        await client.get_chat(chat_id)  # refuse arbitrary/unknown chats
        adapter = PyMaxHistoryChat(client, chat_id, asyncio.get_running_loop())
        transport = MaxMessageTransport(adapter, role="host")
        # Never execute historical requests after a restart.
        historic = await asyncio.to_thread(adapter.read_debug_messages, 100)
        transport.seen_frames.update(frame for raw in historic
                                     if (frame := extract_frame(raw)))
        worker = SessionPCWorker(transport, secret, runner)
        print("MAX_VPS_SESSION_EGRESS_READY", flush=True)
        while True:
            await asyncio.to_thread(worker.process_sync)
            await asyncio.sleep(poll_seconds)
    finally:
        await asyncio.to_thread(runner.stop)
        await client.close()


def read_max_transport_key(environment: dict[str, str] | None = None) -> bytes:
    env = os.environ if environment is None else environment
    legacy = env.get("MAX_VPN_SHARED_SECRET", "").encode("utf-8")
    if len(legacy) >= 32:
        return legacy
    # Pairing secret remains on the VPS and the paired Android device.
    # A distinct domain-separated key prevents reuse across protocols.
    pairing = env.get("MAXVPN_RELAY_TOKEN", "").encode("utf-8")
    if len(pairing) < 40:
        raise ValueError("owner must configure MAXVPN_RELAY_TOKEN before enabling MAX transport")
    return hashlib.sha256(b"MAXVPN-MAX-TRANSPORT-V1:" + pairing).digest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Opt-in MAX transport through one authorized account")
    parser.add_argument("--chat-id", type=int, required=True)
    parser.add_argument("--session-dir", type=Path, default=Path("/var/lib/maxvpn/max-web"))
    parser.add_argument("--poll", type=float, default=3.0)
    args = parser.parse_args()
    secret = read_max_transport_key()
    asyncio.run(run(args.chat_id, session_dir=args.session_dir, secret=secret,
                    poll_seconds=args.poll))


if __name__ == "__main__":
    main()
