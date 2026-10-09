"""Unit contract for the VPS MAX bridge, without MAX account access."""
import asyncio
import unittest
from dataclasses import dataclass

from server.max_egress import MaxQrForOperator, PyMaxHistoryChat, RejectWebPassword, read_max_transport_key


@dataclass
class Message:
    chat_id: int
    text: str


class DummyPyMax:
    def __init__(self):
        self.sent = []
        self.history = [Message(123, "M0FD-TUNNEL-V1:test"),
                        Message(999, "message from another chat"),
                        Message(123, "hello unrelated chat content")]

    async def send_message(self, *, chat_id: int, text: str):
        self.sent.append((chat_id, text))

    async def fetch_history(self, *, chat_id: int, backward: int, get_messages: bool):
        return list(self.history)


class MaxEgressAdapterTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.client = DummyPyMax()
        self.bridge = PyMaxHistoryChat(self.client, 123, asyncio.get_running_loop())

    async def test_only_opt_in_chat_is_sent(self):
        await asyncio.to_thread(self.bridge.send_text, "debug", "send_debug",
                                "M0FD-TUNNEL-V1:fixture")
        self.assertEqual(self.client.sent, [(123, "M0FD-TUNNEL-V1:fixture")])
        with self.assertRaises(ValueError):
            self.bridge.send_text("private-chat", "send_debug",
                                  "M0FD-TUNNEL-V1:fixture")
        with self.assertRaises(ValueError):
            self.bridge.send_text("debug", "send_debug", "wrong prefix")

    async def test_only_configured_history(self):
        messages = await asyncio.to_thread(self.bridge.read_debug_messages, 10)
        self.assertEqual(messages, ["M0FD-TUNNEL-V1:test", "hello unrelated chat content"])
        with self.assertRaises(ValueError):
            self.bridge.read_debug_messages(1000)

    async def test_pairing_is_key_material_not_direct_reuse(self):
        key = read_max_transport_key({"MAXVPN_RELAY_TOKEN": "s" * 64})
        self.assertEqual(len(key), 32)
        self.assertNotEqual(key, b"s" * 32)
        self.assertEqual(key, read_max_transport_key({"MAXVPN_RELAY_TOKEN": "s" * 64}))
        self.assertNotEqual(key, read_max_transport_key({"MAXVPN_RELAY_TOKEN": "t" * 64}))
        with self.assertRaises(ValueError):
            read_max_transport_key({})

    async def test_no_account_password_collected(self):
        with self.assertRaisesRegex(RuntimeError, "official MAX"):
            await RejectWebPassword().get_password("hint")

    async def test_rejects_invalid_qr_source(self):
        with self.assertRaises(ValueError):
            await MaxQrForOperator().show_qr("javascript:alert(1)")


if __name__ == "__main__":
    unittest.main()
