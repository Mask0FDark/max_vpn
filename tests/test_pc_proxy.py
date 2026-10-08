import asyncio
import unittest
from bridge.pc_proxy import parse_connect, handle

class ProxyTests(unittest.IsolatedAsyncioTestCase):
    def test_parse(self):
        self.assertEqual(parse_connect(b"CONNECT example.org:443 HTTP/1.1\r\n"), ("example.org", 443))
        with self.assertRaises(ValueError):
            parse_connect(b"GET / HTTP/1.1\r\n")
    async def test_local_tunnel(self):
        async def echo(reader, writer):
            try:
                data = await reader.readexactly(4)
                writer.write(data)
                await writer.drain()
            finally:
                writer.close()
        echo_server = await asyncio.start_server(echo, "127.0.0.1", 0)
        echo_port = echo_server.sockets[0].getsockname()[1]
        proxy_server = await asyncio.start_server(handle, "127.0.0.1", 0)
        proxy_port = proxy_server.sockets[0].getsockname()[1]
        try:
            reader, writer = await asyncio.open_connection("127.0.0.1", proxy_port)
            writer.write(f"CONNECT 127.0.0.1:{echo_port} HTTP/1.1\r\nHost: localhost\r\n\r\n".encode())
            await writer.drain()
            self.assertIn(b"200 Connection Established", await reader.readuntil(b"\r\n\r\n"))
            writer.write(b"PING")
            await writer.drain()
            self.assertEqual(await asyncio.wait_for(reader.readexactly(4), 5), b"PING")
            writer.close()
            await writer.wait_closed()
        finally:
            proxy_server.close()
            echo_server.close()
            await proxy_server.wait_closed()
            await echo_server.wait_closed()
