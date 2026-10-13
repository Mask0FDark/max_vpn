"""Regression: live MAX egress must not continuously query chat history."""
import asyncio
import unittest
from types import SimpleNamespace
from server.max_egress import PyMaxHistoryChat


class NoHistoryClient:
    def fetch_history(self, **kwargs):
        raise AssertionError("MAX history polling is forbidden in event mode")


class PyMaxEventTransportTests(unittest.TestCase):
    def test_live_events_are_drained_and_chat_scoped(self):
        loop = asyncio.new_event_loop()
        try:
            chat = PyMaxHistoryChat(NoHistoryClient(), 123, loop, event_mode=True)
            chat.accept_event(SimpleNamespace(chat_id=456, text="M0FD-TUNNEL-V1:wrong"))
            chat.accept_event(SimpleNamespace(chat_id=123, text="normal message"))
            chat.accept_event(SimpleNamespace(chat_id=123, text="M0FD-TUNNEL-V1:one"))
            chat.accept_event(SimpleNamespace(chat_id=123, text="M0FD-TUNNEL-V1:two"))
            self.assertEqual(chat.read_debug_messages(1), ["M0FD-TUNNEL-V1:one"])
            self.assertEqual(chat.read_debug_messages(100), ["M0FD-TUNNEL-V1:two"])
            self.assertEqual(chat.read_debug_messages(100), [])
        finally:
            loop.close()

    def test_event_queue_is_bounded(self):
        loop = asyncio.new_event_loop()
        try:
            chat = PyMaxHistoryChat(NoHistoryClient(), 123, loop, event_mode=True)
            for i in range(1100):
                chat.accept_event(SimpleNamespace(chat_id=123, text=f"M0FD-TUNNEL-V1:{i}"))
            self.assertEqual(len(chat._events), 1024)
            self.assertEqual(chat.read_debug_messages(1), ["M0FD-TUNNEL-V1:76"])
        finally:
            loop.close()


if __name__ == "__main__":
    unittest.main()
