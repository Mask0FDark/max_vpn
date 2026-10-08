import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
from companion.max_chat import CompanionMaxChat

class CompanionChatTests(unittest.TestCase):
    def test_explicit_chat_id_required(self):
        for wrong in ("", "favorites", "../1", "123/other", "0?x=y"):
            with self.subTest(wrong=wrong):
                with self.assertRaises(ValueError):
                    CompanionMaxChat(wrong)
        self.assertEqual(CompanionMaxChat("-998").chat_id, "-998")

    def test_reject_other_chats_and_actions(self):
        chat = CompanionMaxChat("-998")
        with self.assertRaises(ValueError):
            chat.send_text("notifications", "send_debug", "hello")
        with self.assertRaises(ValueError):
            chat.send_text("debug", "send_critical", "hello")
        with self.assertRaises(ValueError):
            chat.read_debug_messages(1000)

    def test_reuse_authorized_page(self):
        chat = CompanionMaxChat("-998")
        page = Mock()
        page.url = "https://web.max.ru/-998"
        chat.page = page
        self.assertIs(chat._chat_page(), page)
        page.goto.assert_not_called()
        page.locator.return_value.first.wait_for.assert_called_once()

    def test_navigates_only_to_configured_chat(self):
        chat = CompanionMaxChat("-998")
        page = Mock()
        page.url = "https://web.max.ru/"
        def goto(destination, **kwargs):
            page.url = destination
        page.goto.side_effect = goto
        chat.page = page
        chat._chat_page()
        self.assertEqual(page.url, "https://web.max.ru/-998")

if __name__ == "__main__":
    unittest.main()
