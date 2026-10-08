"""Local-only HTTP CONNECT proxy for the PC endpoint; no MAX transport yet."""
from __future__ import annotations
import argparse
import asyncio
import ipaddress
import socket

MAX_HEADER = 16384

def parse_connect(header: bytes) -> tuple[str, int]:
    try:
        line = header.split(b"\r\n", 1)[0].decode("ascii")
        method, target, version = line.split(" ")
        if method != "CONNECT" or version not in ("HTTP/1.0", "HTTP/1.1"):
            raise ValueError("unsupported request")
        if target.startswith("["):
            host, separator, port = target[1:].partition("]:")
            if not separator:
                raise ValueError("bad IPv6 authority")
        else:
            host, separator, port = target.rpartition(":")
            if not separator:
                raise ValueError("missing port")
        port_number = int(port)
        if not host or not (1 <= port_number <= 65535):
            raise ValueError("invalid target")
        return host, port_number
    except (UnicodeDecodeError, ValueError) as exc:
        raise ValueError("bad CONNECT request") from exc

async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while chunk := await reader.read(32768):
            writer.write(chunk)
            await writer.drain()
    except (ConnectionError, OSError):
        pass
    finally:
        try:
            writer.write_eof()
        except (AttributeError, OSError, RuntimeError):
            pass

async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    upstream = None
    try:
        header = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 10)
        if len(header) > MAX_HEADER:
            raise ValueError("large header")
        host, port = parse_connect(header)
        upstream_reader, upstream = await asyncio.wait_for(asyncio.open_connection(host, port), 15)
        writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        await writer.drain()
        a = asyncio.create_task(pipe(reader, upstream))
        b = asyncio.create_task(pipe(upstream_reader, writer))
        done, pending = await asyncio.wait((a, b), return_when=asyncio.FIRST_COMPLETED)
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)
    except (ValueError, asyncio.IncompleteReadError, asyncio.LimitOverrunError):
        writer.write(b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
    except (OSError, asyncio.TimeoutError):
        writer.write(b"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
    finally:
        if upstream:
            upstream.close()
            await upstream.wait_closed()
        try:
            await writer.drain()
        except (OSError, ConnectionError):
            pass
        writer.close()
        await writer.wait_closed()

async def serve(port: int) -> None:
    # Localhost-only: avoid exposing a public/open proxy.
    server = await asyncio.start_server(handle, "127.0.0.1", port)
    print(f"PC_PROXY_READY=127.0.0.1:{port}", flush=True)
    async with server:
        await server.serve_forever()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="PC-only HTTP CONNECT proxy (not MAX VPN)")
    parser.add_argument("--port", type=int, default=18762)
    args = parser.parse_args()
    asyncio.run(serve(args.port))
