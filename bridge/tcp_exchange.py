"""Bounded single-request TCP relay protocol for later MAX transport integration.

No remote endpoint is exposed: this component performs one short TCP exchange,
not a production VPN or an unrestricted persistent socket tunnel.
"""
from __future__ import annotations

import asyncio
import json
import base64

MAX_REPLY_BYTES = 12000

def request_payload(host: str, port: int, payload: bytes) -> bytes:
    if not host or len(host) > 253 or any(c.isspace() for c in host):
        raise ValueError("invalid host")
    if not 1 <= port <= 65535 or len(payload) > 8192:
        raise ValueError("invalid port or payload size")
    return json.dumps({"host": host, "port": port,
        "data": base64.b64encode(payload).decode("ascii")}, separators=(",", ":")).encode("ascii")

async def execute_request(request: bytes) -> bytes:
    try:
        item = json.loads(request)
        host, port = item["host"], item["port"]
        data = base64.b64decode(item["data"], validate=True)
        request_payload(host, port, data)
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), 5)
        try:
            writer.write(data)
            await writer.drain()
            reply = await asyncio.wait_for(reader.read(MAX_REPLY_BYTES), 5)
        finally:
            writer.close()
            await writer.wait_closed()
        return reply
    except (KeyError, ValueError, TypeError, TimeoutError, OSError) as exc:
        raise RuntimeError("TCP relay request failed") from exc
