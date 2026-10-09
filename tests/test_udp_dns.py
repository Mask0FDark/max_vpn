import asyncio
import json
import unittest
from unittest.mock import AsyncMock

from bridge.tcp_sessions import SessionManager, SessionRunner, encode_command, decode_result

class UdpDnsTests(unittest.IsolatedAsyncioTestCase):
    async def test_command_rejects_non_dns_ports_and_oversized_data(self):
        for port in (1,80,443,5353):
            with self.assertRaises(ValueError):
                encode_command("udp",host="1.1.1.1",port=port,data=b"x")
        with self.assertRaises(ValueError):
            encode_command("udp",host="1.1.1.1",port=53,data=b"x"*1201)

    async def test_udp_dispatch_returns_bounded_dns_payload(self):
        manager=SessionManager()
        manager.exchange_udp=AsyncMock(return_value=b"DNS RESPONSE")
        result=decode_result(await manager.dispatch(
            encode_command("udp", host="1.1.1.1",port=53,data=b"DNS QUERY")))
        self.assertEqual(result["data"],b"DNS RESPONSE")
        manager.exchange_udp.assert_awaited_once_with("1.1.1.1",53,b"DNS QUERY")

    async def test_udp_dns_private_target_rejected(self):
        manager=SessionManager()
        query=encode_command("udp",host="127.0.0.1",port=53,data=b"x")
        with self.assertRaises(RuntimeError):
            await manager.dispatch(query)

if __name__=="__main__":
    unittest.main()
