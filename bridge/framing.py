"""Experimental message framing, independent of MAX and existing notifications."""
from __future__ import annotations

import base64
import binascii
import hashlib
import json
import uuid
from dataclasses import dataclass, field

PREFIX = "M0FD-TUNNEL-V1:"
MAX_TEXT_CHARS = 4000
CHUNK_BYTES = 1200


def encode_frames(payload: bytes, *, packet_id: str | None = None) -> list[str]:
    """Frame opaque bytes for a private, consented test chat. No network I/O."""
    if not isinstance(payload, bytes):
        raise TypeError("payload must be bytes")
    packet_id = packet_id or uuid.uuid4().hex
    if len(packet_id) != 32 or any(c not in '0123456789abcdef' for c in packet_id):
        raise ValueError("invalid packet id")
    count = max(1, (len(payload) + CHUNK_BYTES - 1) // CHUNK_BYTES)
    if count > 256:
        raise ValueError("packet too large")
    checksum = hashlib.sha256(payload).hexdigest()
    frames = []
    for index in range(count):
        chunk = payload[index * CHUNK_BYTES:(index + 1) * CHUNK_BYTES]
        frame = PREFIX + json.dumps({"id": packet_id, "i": index, "n": count, "sha256": checksum,
                                      "data": base64.b64encode(chunk).decode('ascii')}, separators=(',', ':'))
        if len(frame) > MAX_TEXT_CHARS:
            raise ValueError("frame exceeds text limit")
        frames.append(frame)
    return frames


@dataclass
class FrameCollector:
    """Reassemble out-of-order or repeated frames; verify integrity."""
    packets: dict[str, dict[int, bytes]] = field(default_factory=dict)
    metadata: dict[str, tuple[int, str]] = field(default_factory=dict)

    def accept(self, frame: str) -> bytes | None:
        if not frame.startswith(PREFIX):
            raise ValueError("unknown frame prefix")
        if len(frame) > MAX_TEXT_CHARS:
            raise ValueError("frame too large")
        try:
            body = json.loads(frame[len(PREFIX):])
            pid, index, count, checksum = (body[k] for k in ('id', 'i', 'n', 'sha256'))
        except (ValueError, TypeError, KeyError) as exc:
            raise ValueError("malformed frame") from exc
        if not isinstance(pid, str) or len(pid) != 32 or any(c not in '0123456789abcdef' for c in pid):
            raise ValueError("bad packet id")
        if type(index) is not int or type(count) is not int or not (0 <= index < count <= 256):
            raise ValueError("invalid fragment indices")
        if not isinstance(checksum, str) or len(checksum) != 64:
            raise ValueError("invalid checksum")
        try:
            chunk = base64.b64decode(body['data'], validate=True)
        except (binascii.Error, ValueError, TypeError, KeyError) as exc:
            raise ValueError("invalid fragment encoding") from exc
        if len(chunk) > CHUNK_BYTES:
            raise ValueError("oversized fragment")
        meta = (count, checksum)
        if pid in self.metadata and self.metadata[pid] != meta:
            raise ValueError("conflicting packet metadata")
        self.metadata[pid] = meta
        parts = self.packets.setdefault(pid, {})
        if index in parts and parts[index] != chunk:
            raise ValueError("conflicting duplicate")
        parts[index] = chunk
        if len(parts) != count:
            return None
        payload = b''.join(parts[i] for i in range(count))
        del self.packets[pid]
        del self.metadata[pid]
        if hashlib.sha256(payload).hexdigest() != checksum:
            raise ValueError("packet checksum mismatch")
        return payload
