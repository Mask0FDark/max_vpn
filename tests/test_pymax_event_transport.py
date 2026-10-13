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


class PyMaxWakeTests(unittest.IsolatedAsyncioTestCase):
    async def test_new_encrypted_frame_wakes_host_without_history_query(self):
        chat = PyMaxHistoryChat(
            NoHistoryClient(), 123, asyncio.get_running_loop(), event_mode=True
        )
        waiter = asyncio.create_task(chat.wait_for_incoming(1.0))
        await asyncio.sleep(0)
        chat.accept_event(SimpleNamespace(chat_id=123, text="M0FD-TUNNEL-V1:new"))
        await asyncio.wait_for(waiter, timeout=0.5)
        self.assertEqual(
            chat.read_debug_messages(1), ["M0FD-TUNNEL-V1:new"]
        )

    async def test_unrelated_chat_does_not_wake_host(self):
        chat = PyMaxHistoryChat(
            NoHistoryClient(), 123, asyncio.get_running_loop(), event_mode=True
        )
        chat.accept_event(SimpleNamespace(chat_id=456, text="M0FD-TUNNEL-V1:other"))
        await chat.wait_for_incoming(0.05)
        self.assertEqual(chat.read_debug_messages(5), [])


if __name__ == "__main__":
    unittest.main()
