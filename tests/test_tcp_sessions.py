import asyncio
import unittest

from bridge.max_rpc import MaxMessageTransport
from bridge.pc_worker import wrap, unwrap
from bridge.session_relay import SessionPCWorker
from bridge.tcp_sessions import SessionManager, SessionRunner, decode_result, encode_command

KEY = b"session-test-secret-length-at-least-32-bytes"


class SharedChat:
    def __init__(self):
        self.messages = []
    def send_text(self, name, action, text):
        self.messages.append(text)
    def read_debug_messages(self, limit=100):
        return self.messages[-limit:]


class SessionTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        async def echo(reader, writer):
            try:
                while data := await reader.read(4096):
                    writer.write(data)
                    await writer.drain()
            except (ConnectionError, OSError):
                pass
            finally:
                writer.close()
                await writer.wait_closed()
        self.server = await asyncio.start_server(echo, "127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]
        self.runner = SessionRunner(SessionManager(max_sessions=2, allow_private=True))

    async def asyncTearDown(self):
        await asyncio.to_thread(self.runner.stop)
        self.server.close()
        await self.server.wait_closed()

    async def command(self, op: bytes):
        return decode_result(await asyncio.to_thread(self.runner.execute, op))

    async def test_open_write_read_twice_and_close(self):
        opened = await self.command(encode_command("open", host="127.0.0.1", port=self.port))
        key = opened["session"]
        for payload in (b"first", b"second" * 200):
            self.assertEqual((await self.command(encode_command("write", session=key, data=payload)))["status"], "ok")
            result = await self.command(encode_command("read", session=key))
            self.assertEqual(result["status"], "ok")
            self.assertEqual(result["data"], payload)
        self.assertEqual((await self.command(encode_command("close", session=key)))["status"], "ok")
        with self.assertRaises(RuntimeError):
            await self.command(encode_command("read", session=key))

    async def test_rejected_private_egress_by_default(self):
        strict = SessionRunner(SessionManager(allow_private=False))
        try:
            with self.assertRaises(RuntimeError):
                await asyncio.to_thread(strict.execute, encode_command("open", host="127.0.0.1", port=self.port))
        finally:
            await asyncio.to_thread(strict.stop)

    async def test_session_limit_and_unknown_ids(self):
        first = await self.command(encode_command("open", host="127.0.0.1", port=self.port))
        second = await self.command(encode_command("open", host="127.0.0.1", port=self.port))
        self.assertNotEqual(first["session"], second["session"])
        with self.assertRaises(RuntimeError):
            await self.command(encode_command("open", host="127.0.0.1", port=self.port))
        with self.assertRaises(RuntimeError):
            await self.command(encode_command("close", session="0" * 32))

    async def test_encrypted_session_through_same_account_chat(self):
        chat = SharedChat()
        mobile = MaxMessageTransport(chat, role="mobile")
        host = MaxMessageTransport(chat, role="host")
        worker = SessionPCWorker(host, KEY, self.runner)

        async def request(data):
            import uuid
            rid = uuid.uuid4().hex
            mobile.send("request", wrap(KEY, rid, data), rid)
            self.assertEqual(mobile.receive(), [])
            self.assertEqual(await asyncio.to_thread(worker.process_sync), 1)
            response = [p for p in mobile.receive() if p["request_id"] == rid]
            self.assertEqual(len(response), 1)
            self.assertEqual(response[0]["kind"], "response")
            self.assertEqual(host.receive(), [])
            return decode_result(unwrap(KEY, rid, response[0]["payload"]))

        opened = await request(encode_command("open", host="127.0.0.1", port=self.port))
        key = opened["session"]
        await request(encode_command("write", session=key, data=b"PING1"))
        self.assertEqual((await request(encode_command("read", session=key)))["data"], b"PING1")
        await request(encode_command("write", session=key, data=b"PING2"))
        self.assertEqual((await request(encode_command("read", session=key)))["data"], b"PING2")
        self.assertEqual((await request(encode_command("close", session=key)))["status"], "ok")

    def test_command_validation(self):
        for action in ("open", "write", "read", "close", "invalid"):
            if action == "invalid":
                with self.assertRaises(ValueError):
                    encode_command(action)
        with self.assertRaises(ValueError):
            encode_command("write", session="f" * 32, data=b"x" * 2049)
        with self.assertRaises(ValueError):
            encode_command("open", host="127.0.0.1", port=0)


if __name__ == "__main__":
    unittest.main()
