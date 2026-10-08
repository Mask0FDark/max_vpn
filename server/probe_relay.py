"""On-server authorized WSS relay smoke test (prints no credentials).

Use: docker compose exec -T maxvpn-web python -m server.probe_relay
"""
from __future__ import annotations
import asyncio
import os
import ssl
from urllib.parse import quote
from websockets.asyncio.client import connect

URL = "wss://max-vpn.mask-0f-darkness.ru/relay"


async def main():
    token = os.environ.get("MAXVPN_RELAY_TOKEN", "")
    if len(token) < 40:
        raise RuntimeError("relay pairing is not configured")
    opts = dict(additional_headers={"X-MAXVPN-Token": token}, open_timeout=15,
                close_timeout=5, max_size=65536, ping_interval=20)
    async with connect(URL + "/check", **opts) as ws:
        assert await asyncio.wait_for(ws.recv(), 12) == "paired"
    print("VPS_WSS_AUTHENTICATED_PAIR_PASS", flush=True)

    async with connect(URL + "/tcp?host=" + quote("example.com") + "&port=443", **opts) as ws:
        context = ssl.create_default_context()
        rx, tx = ssl.MemoryBIO(), ssl.MemoryBIO()
        tls = context.wrap_bio(rx, tx, server_hostname="example.com")

        async def transmit():
            while tx.pending:
                await ws.send(tx.read(16384))

        complete = False
        for _ in range(16):
            try:
                tls.do_handshake()
                complete = True
            except ssl.SSLWantReadError:
                pass
            await transmit()
            if complete: break
            data = await asyncio.wait_for(ws.recv(), 20)
            if not isinstance(data, bytes):
                raise RuntimeError("nonbinary tunnel response")
            rx.write(data)
        if not complete: raise TimeoutError("TLS handshake did not finish")
        print("VPS_WSS_TLS_CERT_VERIFIED_PASS", flush=True)
        tls.write(b"GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n")
        await transmit()
        plaintext = b""
        for _ in range(20):
            try:
                plaintext += tls.read(16384)
            except (ssl.SSLWantReadError, ssl.SSLZeroReturnError):
                pass
            if b"HTTP/1.1 200" in plaintext and b"\r\n\r\n" in plaintext:
                break
            data = await asyncio.wait_for(ws.recv(), 20)
            if not isinstance(data, bytes):
                raise RuntimeError("nonbinary response")
            rx.write(data)
        assert b"HTTP/1.1 200" in plaintext, "HTTPS HTTP 200 not received"
    print("VPS_WSS_EXTERNAL_HTTPS_200_PASS", flush=True)


if __name__ == "__main__":
    asyncio.run(main())
