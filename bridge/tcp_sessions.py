"""Persistent bounded TCP sessions for the encrypted MAX message protocol.

The sockets live on a dedicated asyncio event loop. A browser-automation
worker can therefore process subsequent MAX messages synchronously without
destroying the socket between requests. This is a transport prototype, not a
packet-level VPN. The default egress policy denies private/local addresses.
"""
from __future__ import annotations

import asyncio
import base64
import binascii
import ipaddress
import json
import socket
import threading
import time
import uuid
from concurrent.futures import TimeoutError as FutureTimeout
from dataclasses import dataclass


CHUNK_LIMIT = 2048
MAX_SESSIONS = 8


def encode_command(action: str, *, session: str | None = None, host: str | None = None,
                   port: int | None = None, data: bytes = b"") -> bytes:
    if action not in ("open", "write", "read", "close"):
        raise ValueError("unsupported action")
    if type(data) is not bytes or len(data) > CHUNK_LIMIT:
        raise ValueError("oversized command data")
    packet = {"v": 1, "action": action}
    if action == "open":
        if not host or len(host) > 253 or any(c.isspace() for c in host):
            raise ValueError("invalid host")
        if type(port) is not int or not 1 <= port <= 65535:
            raise ValueError("invalid port")
        packet.update(host=host, port=port)
    else:
        if not session or len(session) != 32 or any(c not in "0123456789abcdef" for c in session):
            raise ValueError("invalid session ID")
        packet["session"] = session
        if action == "write":
            packet["data"] = base64.b64encode(data).decode("ascii")
    return json.dumps(packet, separators=(",", ":")).encode("ascii")


def decode_result(raw: bytes) -> dict:
    try:
        obj = json.loads(raw)
        if obj.get("v") != 1 or obj.get("status") not in ("ok", "eof", "timeout"):
            raise ValueError("bad session result")
        if "data" in obj:
            obj["data"] = base64.b64decode(obj["data"], validate=True)
        return obj
    except (ValueError, KeyError, TypeError, binascii.Error) as exc:
        raise ValueError("invalid session response") from exc


def _result(status: str = "ok", **kwargs) -> bytes:
    result = {"v": 1, "status": status, **kwargs}
    return json.dumps(result, separators=(",", ":")).encode("ascii")


@dataclass
class _Session:
    reader: asyncio.StreamReader
    writer: asyncio.StreamWriter
    last_used: float


class SessionManager:
    def __init__(self, max_sessions: int = MAX_SESSIONS, *,
                 idle_timeout: float = 90, allow_private: bool = False):
        if not 1 <= max_sessions <= 64 or idle_timeout <= 0:
            raise ValueError("invalid session policy")
        self.sessions: dict[str, _Session] = {}
        self.max_sessions = max_sessions
        self.idle_timeout = idle_timeout
        self.allow_private = allow_private

    async def _get_address(self, host: str, port: int) -> str:
        records = await asyncio.wait_for(
            asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM), 5)
        for family, _, _, _, address in records:
            if family not in (socket.AF_INET, socket.AF_INET6):
                continue
            ip = ipaddress.ip_address(address[0])
            if self.allow_private or ip.is_global:
                return address[0]
        raise ValueError("target is not a permitted public address")

    async def _cleanup(self) -> None:
        now = time.monotonic()
        for key, item in list(self.sessions.items()):
            if now - item.last_used > self.idle_timeout:
                await self.close(key)

    async def open(self, host: str, port: int) -> str:
        encode_command("open", host=host, port=port)
        await self._cleanup()
        if len(self.sessions) >= self.max_sessions:
            raise RuntimeError("session capacity exceeded")
        # Connect only to an IP vetted above, preventing DNS re-resolution.
        address = await self._get_address(host, port)
        reader, writer = await asyncio.wait_for(asyncio.open_connection(address, port), 6)
        key = uuid.uuid4().hex
        self.sessions[key] = _Session(reader, writer, time.monotonic())
        return key

    async def write(self, key: str, data: bytes) -> None:
        if len(data) > CHUNK_LIMIT:
            raise ValueError("write exceeds chunk limit")
        item = self.sessions.get(key)
        if item is None:
            raise ValueError("unknown session")
        item.writer.write(data)
        await asyncio.wait_for(item.writer.drain(), 5)
        item.last_used = time.monotonic()

    async def read(self, key: str) -> tuple[str, bytes]:
        item = self.sessions.get(key)
        if item is None:
            raise ValueError("unknown session")
        try:
            payload = await asyncio.wait_for(item.reader.read(CHUNK_LIMIT), 0.4)
            item.last_used = time.monotonic()
            if payload:
                return "ok", payload
            await self.close(key)
            return "eof", b""
        except asyncio.TimeoutError:
            item.last_used = time.monotonic()
            return "timeout", b""

    async def close(self, key: str) -> None:
        item = self.sessions.pop(key, None)
        if item is not None:
            item.writer.close()
            try:
                await asyncio.wait_for(item.writer.wait_closed(), 3)
            except (ConnectionError, OSError, asyncio.TimeoutError):
                pass

    async def close_all(self) -> None:
        for key in list(self.sessions):
            await self.close(key)

    async def dispatch(self, request: bytes) -> bytes:
        try:
            obj = json.loads(request)
            if type(obj) is not dict or obj.get("v") != 1:
                raise ValueError("invalid command version")
            action = obj["action"]
            await self._cleanup()
            if action == "open":
                key = await self.open(obj["host"], obj["port"])
                return _result(session=key)
            key = obj["session"]
            if action == "write":
                data = base64.b64decode(obj["data"], validate=True)
                if len(data) > CHUNK_LIMIT:
                    raise ValueError("payload exceeds chunk limit")
                await self.write(key, data)
                return _result()
            if action == "read":
                state, data = await self.read(key)
                return _result(status=state, data=base64.b64encode(data).decode("ascii"))
            if action == "close":
                if key not in self.sessions:
                    raise ValueError("unknown session")
                await self.close(key)
                return _result()
        except (KeyError, ValueError, TypeError, OSError, ConnectionError,
                TimeoutError, binascii.Error) as exc:
            raise RuntimeError("TCP session operation failed") from exc
        raise RuntimeError("unsupported TCP session action")


class SessionRunner:
    """Owns the event loop so sockets remain alive across synchronous polls."""

    def __init__(self, manager: SessionManager | None = None):
        self.manager = manager or SessionManager()
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self._run, name="maxvpn-tcp-loop", daemon=True)
        self.thread.start()

    def _run(self) -> None:
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()
        self.loop.close()

    def execute(self, data: bytes, timeout: float = 15) -> bytes:
        job = asyncio.run_coroutine_threadsafe(self.manager.dispatch(data), self.loop)
        try:
            return job.result(timeout=timeout)
        except FutureTimeout as exc:
            job.cancel()
            raise RuntimeError("TCP session timeout") from exc

    def stop(self) -> None:
        if not self.thread.is_alive():
            return
        try:
            job = asyncio.run_coroutine_threadsafe(self.manager.close_all(), self.loop)
            job.result(timeout=10)
        finally:
            self.loop.call_soon_threadsafe(self.loop.stop)
            self.thread.join(timeout=10)
