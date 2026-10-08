import asyncio
import unittest
from bridge.tcp_exchange import request_payload, execute_request
from bridge.max_rpc import MaxMessageTransport

class FakeChat:
    def __init__(self):
        self.messages=[]
    def send_text(self, key, action, text):
        self.messages.append(text)
    def read_debug_messages(self, limit=100):
        return self.messages[-limit:]

class TCPExchangeTests(unittest.IsolatedAsyncioTestCase):
    async def test_tcp_over_message_envelopes(self):
        async def echo(reader, writer):
            try:
                writer.write(await reader.readexactly(4))
                await writer.drain()
            finally:
                writer.close()
        server = await asyncio.start_server(echo, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            chat=FakeChat()
            mobile=MaxMessageTransport(chat)
            pc=MaxMessageTransport(chat)
            rid=mobile.send("request", request_payload("127.0.0.1",port,b"PING"))
            requests=[m for m in pc.receive() if m["request_id"]==rid]
            self.assertEqual(len(requests),1)
            response=await execute_request(requests[0]["payload"])
            pc.send("response",response,rid)
            self.assertEqual(mobile.await_reply(rid,1),b"PING")
        finally:
            server.close()
            await server.wait_closed()

    def test_invalid_targets(self):
        with self.assertRaises(ValueError):
            request_payload("test host",443,b"a")
        with self.assertRaises(ValueError):
            request_payload("127.0.0.1",0,b"a")
