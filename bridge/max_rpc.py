"""Request/reply message transport for authorized MAX test chats.

The adapter works only through an existing explicitly allowlisted MAX
automation profile. No credentials, cookies, or profile data are exported.
"""
from __future__ import annotations

import base64
import json
import time
import uuid
from typing import Protocol

from bridge.framing import encode_frames, FrameCollector
from bridge.max_debug import extract_frame

class Chat(Protocol):
    def send_text(self, chat_key: str, action: str, text: str) -> None: ...
    def read_debug_messages(self, limit: int = 50) -> list[str]: ...

def make_envelope(kind: str, payload: bytes, request_id: str | None = None) -> bytes:
    if kind not in ("request", "response", "error"):
        raise ValueError("invalid message kind")
    request_id = request_id or uuid.uuid4().hex
    if len(request_id) != 32 or any(c not in "0123456789abcdef" for c in request_id):
        raise ValueError("invalid request identifier")
    return json.dumps({"version":1, "kind":kind, "request_id":request_id,
        "payload":base64.b64encode(payload).decode("ascii")}, separators=(",", ":")).encode("ascii")

def parse_envelope(data: bytes) -> dict:
    try:
        raw = json.loads(data.decode("ascii"))
        if raw["version"] != 1 or raw["kind"] not in ("request", "response", "error"):
            raise ValueError("invalid envelope")
        rid = raw["request_id"]
        if not isinstance(rid, str) or len(rid) != 32 or any(c not in "0123456789abcdef" for c in rid):
            raise ValueError("invalid id")
        return {"kind":raw["kind"], "request_id":rid,
                "payload":base64.b64decode(raw["payload"], validate=True)}
    except (KeyError, ValueError, TypeError, UnicodeDecodeError) as exc:
        raise ValueError("invalid envelope") from exc

class MaxMessageTransport:
    def __init__(self, chat: Chat):
        self.chat = chat
        self.seen_frames: set[str] = set()
        self.collector = FrameCollector()

    def send(self, kind: str, payload: bytes, request_id: str | None = None) -> str:
        envelope = make_envelope(kind, payload, request_id)
        rid = json.loads(envelope)["request_id"]
        for frame in encode_frames(envelope):
            self.chat.send_text("debug", "send_debug", frame)
        return rid

    def receive(self) -> list[dict]:
        result = []
        for message in self.chat.read_debug_messages(100):
            frame = extract_frame(message)
            if not frame or frame in self.seen_frames:
                continue
            self.seen_frames.add(frame)
            try:
                payload = self.collector.accept(frame)
                if payload is not None:
                    result.append(parse_envelope(payload))
            except (ValueError, KeyError):
                continue
        return result

    def await_reply(self, request_id: str, timeout: float = 30) -> bytes:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for item in self.receive():
                if item["request_id"] == request_id and item["kind"] == "response":
                    return item["payload"]
                if item["request_id"] == request_id and item["kind"] == "error":
                    raise RuntimeError(item["payload"].decode("utf-8", errors="replace"))
            time.sleep(1)
        raise TimeoutError("no MAX response received")
