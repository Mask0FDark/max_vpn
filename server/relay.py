"""Authenticated WSS TCP/UDP relay for a single paired Android device.

This is a DIRECT HTTPS fallback transport, NOT MAX message transport.
External destinations only, bounded connections and frame sizes.
"""
from __future__ import annotations

import asyncio
import hmac
import ipaddress
import os
import socket
from contextlib import suppress

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

router = APIRouter()
MAX_CONNECTIONS = 20
MAX_FRAME = 65536
MAX_SESSION_SECONDS = 600
_CONNECTIONS = asyncio.Semaphore(MAX_CONNECTIONS)


def authorized(headers) -> bool:
    expected = os.getenv("MAXVPN_RELAY_TOKEN", "")
    provided = headers.get("x-maxvpn-token", "")
    return len(expected) >= 40 and len(provided) == len(expected) and hmac.compare_digest(expected, provided)


async def resolve_public(host: str, port: int, *, socktype: int) -> tuple[int, tuple]:
    if not host or len(host) > 253 or not isinstance(port, int) or not 1 <= port <= 65535:
        raise ValueError("invalid destination")
    results = await asyncio.wait_for(
        asyncio.get_running_loop().getaddrinfo(host, port, type=socktype), 5)
    for family, kind, proto, _, address in results:
        if family in (socket.AF_INET, socket.AF_INET6) and ipaddress.ip_address(address[0]).is_global:
            return family, address
    raise ValueError("destination must resolve to public IP")


async def upstream_stream(ws: WebSocket, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
    async def receive():
        while True:
            data = await ws.receive_bytes()
            if not data or len(data) > MAX_FRAME:
                break
            writer.write(data)
            await asyncio.wait_for(writer.drain(), 15)

    async def send():
        while True:
            data = await asyncio.wait_for(reader.read(16384), 60)
            if not data:
                break
            await ws.send_bytes(data)

    tasks = [asyncio.create_task(receive()), asyncio.create_task(send())]
    try:
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED, timeout=MAX_SESSION_SECONDS)
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


@router.websocket("/relay/check")
async def check(ws: WebSocket):
    if not authorized(ws.headers):
        await ws.close(code=1008)
        return
    await ws.accept()
    await ws.send_text("paired")
    await ws.close()


@router.websocket("/relay/tcp")
async def tcp(ws: WebSocket, host: str, port: int):
    if not authorized(ws.headers):
        await ws.close(code=1008)
        return
    if _CONNECTIONS.locked():
        await ws.close(code=1013)
        return
    async with _CONNECTIONS:
        try:
            _, address = await resolve_public(host, port, socktype=socket.SOCK_STREAM)
            reader, writer = await asyncio.wait_for(asyncio.open_connection(*address[:2]), 10)
        except (OSError, ValueError, TimeoutError):
            await ws.close(code=1008)
            return
        try:
            await ws.accept()
            await upstream_stream(ws, reader, writer)
        except (WebSocketDisconnect, RuntimeError, ConnectionError, OSError):
            pass
        finally:
            writer.close()
            with suppress(Exception):
                await asyncio.wait_for(writer.wait_closed(), 3)
            with suppress(Exception):
                await ws.close()


class DatagramExchange:
    def __init__(self, queue: asyncio.Queue):
        self.queue = queue

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, address):
        if not self.queue.full():
            self.queue.put_nowait((data, address))

    def error_received(self, exc):
        pass


@router.websocket("/relay/udp")
async def udp(ws: WebSocket):
    if not authorized(ws.headers):
        await ws.close(code=1008)
        return
    if _CONNECTIONS.locked():
        await ws.close(code=1013)
        return
    async with _CONNECTIONS:
        queue = asyncio.Queue(maxsize=100)
        loop = asyncio.get_running_loop()
        transport, _ = await loop.create_datagram_endpoint(
            lambda: DatagramExchange(queue), local_addr=("0.0.0.0", 0))
        try:
            await ws.accept()
            async def receive():
                while True:
                    datagram = await ws.receive_bytes()
                    if len(datagram) < 4 or len(datagram) > 65507:
                        continue
                    # 1-byte length + host ASCII + 2-byte port + datagram
                    name_length = datagram[0]
                    if not 1 <= name_length <= 253 or len(datagram) < name_length + 4:
                        continue
                    try:
                        host = datagram[1:1+name_length].decode("ascii")
                        port = int.from_bytes(datagram[1+name_length:3+name_length], "big")
                        _, address = await resolve_public(host, port, socktype=socket.SOCK_DGRAM)
                    except (ValueError, UnicodeDecodeError, OSError, TimeoutError):
                        continue
                    transport.sendto(datagram[3+name_length:], address)

            async def send():
                while True:
                    payload, address = await asyncio.wait_for(queue.get(), 60)
                    host = address[0].encode("ascii")
                    if len(host) <= 253:
                        await ws.send_bytes(bytes([len(host)]) + host +
                            int(address[1]).to_bytes(2, "big") + payload)

            jobs = [asyncio.create_task(receive()), asyncio.create_task(send())]
            try:
                await asyncio.wait(jobs, return_when=asyncio.FIRST_COMPLETED, timeout=MAX_SESSION_SECONDS)
            finally:
                for job in jobs: job.cancel()
                await asyncio.gather(*jobs, return_exceptions=True)
        except (WebSocketDisconnect, RuntimeError, ConnectionError, OSError):
            pass
        finally:
            transport.close()
            with suppress(Exception):
                await ws.close()
