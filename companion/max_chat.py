"""Use the Companion-authorized private Edge profile for an opt-in MAX chat.

This adapter reads and writes only one explicit chat ID; it never extracts
cookies or browses unrelated personal conversations. The browser must be
closed after manual login before this adapter opens the same profile.
"""
from __future__ import annotations

import os
import re
from pathlib import Path
from urllib.parse import urlparse

from companion.auth_flow import EDGE_PATHS, MAX_URL, state_root

EDITOR_SELECTOR = (
    '[data-lexical-editor="true"][role="textbox"], '
    '[role="textbox"][contenteditable="true"], '
    '[role="textbox"][contenteditable=""]'
)
SEND_NAME = re.compile(r"^(Send message|Отправить сообщение)$", re.IGNORECASE)


class CompanionMaxChat:
    def __init__(self, chat_id: str, *, profile_dir: Path | None = None, headless: bool = True):
        if not re.fullmatch(r"-?\d{1,20}", chat_id):
            raise ValueError("chat id must be a numeric MAX chat identifier")
        self.chat_id = chat_id
        self.profile_dir = profile_dir or state_root() / "max-web-profile"
        self.headless = headless
        self.playwright = None
        self.context = None
        self.page = None

    def __enter__(self):
        from playwright.sync_api import sync_playwright

        if not self.profile_dir.is_dir():
            raise RuntimeError("no Companion MAX profile: open the login page first")
        edge = next((path for path in EDGE_PATHS if path.is_file()), None)
        if edge is None:
            raise RuntimeError("Microsoft Edge is not installed")
        self.playwright = sync_playwright().start()
        try:
            self.context = self.playwright.chromium.launch_persistent_context(
                user_data_dir=self.profile_dir, executable_path=edge,
                headless=self.headless, no_viewport=True,
                args=["--no-sandbox"] if os.name != "nt" else [],
            )
            self.page = self.context.pages[0] if self.context.pages else self.context.new_page()
            self._chat_page()
        except Exception:
            self.__exit__(None, None, None)
            raise
        return self

    def __exit__(self, exc_type, exc, traceback):
        try:
            if self.context:
                self.context.close()
        finally:
            if self.playwright:
                self.playwright.stop()

    def _chat_page(self):
        if self.page is None:
            raise RuntimeError("MAX browser has not been started")
        target_path = f"/{self.chat_id}"
        if urlparse(self.page.url).path.rstrip("/") != target_path:
            self.page.goto(MAX_URL.rstrip("/") + target_path,
                           wait_until="domcontentloaded", timeout=60000)
        if urlparse(self.page.url).path.rstrip("/") != target_path:
            raise RuntimeError("MAX redirected away from the authorized test chat")
        editor = self.page.locator(EDITOR_SELECTOR).first
        editor.wait_for(state="visible", timeout=15000)
        return self.page

    def send_text(self, chat_key: str, action: str, text: str) -> None:
        if (chat_key, action) != ("debug", "send_debug"):
            raise ValueError("the Companion supports only its configured diagnostic chat")
        if not text or len(text) > 4000:
            raise ValueError("invalid MAX test message")
        page = self._chat_page()
        page.locator(EDITOR_SELECTOR).first.fill(text)
        page.get_by_role("button", name=SEND_NAME).click()
        page.locator(".messageWrapper--isOut", has_text=text).last.wait_for(
            state="visible", timeout=15000
        )

    def read_debug_messages(self, limit: int = 100) -> list[str]:
        if not 1 <= limit <= 100:
            raise ValueError("limit must be between 1 and 100")
        page = self._chat_page()
        messages = page.locator('[class*="messageWrapper"]')
        return [item.inner_text() for item in messages.all()[-limit:]]
