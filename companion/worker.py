"""Optional PC-side MAX VPN diagnostic worker using the Companion login profile.

This mode never loads D:/MAX profiles and only uses an explicitly selected
user-owned MAX test chat. It is still a bounded request/response experiment.
"""
from __future__ import annotations

import argparse
import os
import time

from bridge.max_debug import extract_frame
from bridge.max_rpc import MaxMessageTransport
from bridge.pc_worker import PCWorker
from bridge.session_relay import SessionPCWorker
from companion.max_chat import CompanionMaxChat


def load_shared_key() -> bytes:
    key = os.environ.get("MAX_VPN_SHARED_SECRET", "").encode("utf-8")
    if len(key) < 32:
        raise ValueError("MAX_VPN_SHARED_SECRET must contain at least 32 bytes")
    return key


def run(chat_id: str, poll_seconds: float = 3.0, *, sessions: bool = False) -> None:
    secret = load_shared_key()
    os.environ.setdefault("NODE_OPTIONS", "--max-old-space-size=1024")
    with CompanionMaxChat(chat_id) as browser:
        channel = MaxMessageTransport(browser, role="host")
        # Ignore historical test data when the worker first starts.
        channel.seen_frames.update(
            frame for message in browser.read_debug_messages(100)
            if (frame := extract_frame(message))
        )
        worker = SessionPCWorker(channel, secret) if sessions else PCWorker(channel, secret)
        print("MAX_VPN_SESSION_WORKER_READY" if sessions else "MAX_VPN_COMPANION_WORKER_READY", flush=True)
        try:
            while True:
                worker.process_sync()
                time.sleep(max(1.0, poll_seconds))
        finally:
            if sessions:
                worker.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="MAX VPN diagnostic worker for one account")
    parser.add_argument("--chat-id", required=True, help="ID of a consented, dedicated MAX test chat")
    parser.add_argument("--poll-seconds", type=float, default=3.0)
    parser.add_argument("--sessions", action="store_true", help="Enable persistent TCP sessions (experimental)")
    args = parser.parse_args()
    run(args.chat_id, args.poll_seconds, sessions=args.sessions)
