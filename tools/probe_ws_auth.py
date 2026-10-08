"""External security smoke test: private WSS relay must reject anonymous clients."""
import asyncio
import websockets
from websockets.exceptions import InvalidStatus


async def probe():
    url = "wss://max-vpn.mask-0f-darkness.ru/relay/check"
    try:
        async with websockets.connect(url, open_timeout=12) as ws:
            raise AssertionError("SECURITY_ERROR: anonymous user entered relay")
    except InvalidStatus as exc:
        code = exc.response.status_code
        if code not in (401, 403):
            raise
        print("WSS_RELAY_ANONYMOUS_REJECTED_PASS", code)


if __name__ == "__main__":
    asyncio.run(probe())
