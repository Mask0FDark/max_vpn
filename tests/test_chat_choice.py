import asyncio
import tempfile
import unittest
from pathlib import Path

from server.phone_auth import PhoneAuthentication


class ChatChoiceTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.controller = PhoneAuthentication(session_dir=Path(self.temp.name), chat_id=-99)
        self.controller._client = object()
        self.controller.chat_options = [
            {"id": 123, "title": "Personal", "type": "DIALOG"},
            {"id": 456, "title": "Dedicated test group", "type": "CHAT"},
        ]
        self.controller.phase = "chat_selection_required"
        self.controller.chat_ready = asyncio.Event()

    async def asyncTearDown(self):
        self.temp.cleanup()

    async def test_selects_only_listed_chat_and_wakes_worker(self):
        with self.assertRaises(ValueError):
            self.controller.select_chat(9999)
        with self.assertRaises(ValueError):
            self.controller.select_chat(True)
        self.assertEqual(self.controller.chat_id, -99)
        self.controller.select_chat(456)
        self.assertTrue(self.controller.chat_ready.is_set())
        self.assertEqual(self.controller.chat_id, 456)
        self.assertEqual(len(self.controller.available_chats()), 2)

    async def test_does_not_select_when_no_session(self):
        self.controller._client = None
        with self.assertRaises(RuntimeError):
            self.controller.select_chat(123)
        self.assertEqual(self.controller.available_chats(), [])


if __name__ == "__main__":
    unittest.main()
