"""Consent-based MAX SMS authentication and same-account message egress.

The user's paired Android app submits a phone number and received SMS code
through authenticated HTTPS endpoints. Codes and 2FA passwords exist only in
memory while the official MAX API completes authorization. The PyMax session
is saved on the owner's VPS for reconnects. No QR or fake registration.
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from bridge.max_debug import extract_frame
from bridge.max_rpc import MaxMessageTransport
from bridge.session_relay import SessionPCWorker
from server.max_egress import PyMaxHistoryChat, read_max_transport_key

PHONE_PATTERN = re.compile(r"^\+[1-9][0-9]{7,14}$")


class PhoneAuthentication:
    def __init__(self, *, session_dir: Path, chat_id: int):
        self.dir = session_dir
        self.chat_id = chat_id
        self.phase = "idle"
        self.detail = ""
        self.phone = ""
        self.task: asyncio.Task | None = None
        self.code_waiter: asyncio.Future[str] | None = None
        self.password_waiter: asyncio.Future[str] | None = None
        self.throttle: list[float] = []
        self._lock = asyncio.Lock()
        self._client: Any = None
        self.chat_options: list[dict[str, Any]] = []
        self.chat_ready: asyncio.Event | None = None

    async def begin(self, phone: str) -> None:
        if not PHONE_PATTERN.fullmatch(phone):
            raise ValueError("phone must be an international number, such as +79991234567")
        async with self._lock:
            if self.task is not None and not self.task.done():
                raise RuntimeError("authorization is already in progress")
            if self.phase == "connected":
                raise RuntimeError("session is already authorized")
            now = time.monotonic()
            self.throttle = [t for t in self.throttle if t > now-3600]
            if len(self.throttle) >= 4:
                raise RuntimeError("too many authorization attempts; wait an hour")
            self.throttle.append(now)
            self.phone = phone
            self.phase = "requesting_code"
            self.detail = ""
            self.code_waiter = None
            self.password_waiter = None
            self.task = asyncio.create_task(self._login(phone))

    def status(self) -> dict[str, str]:
        # Do not expose phone or login tokens to callers.
        return {"phase": self.phase, "detail": self.detail}

    def available_chats(self) -> list[dict[str, Any]]:
        return list(self.chat_options) if self._client is not None else []

    def select_chat(self, selected_id: int) -> None:
        if type(selected_id) is not int or selected_id not in {c["id"] for c in self.chat_options}:
            raise ValueError("chat ID not in authenticated account")
        if self._client is None or self.phase != "chat_selection_required" or self.chat_ready is None:
            raise RuntimeError("MAX session not awaiting chat selection")
        self.chat_id = selected_id
        self.chat_ready.set()

    async def get_code(self, phone: str) -> str:
        if phone != self.phone:
            raise ValueError("phone mismatch")
        self.code_waiter = asyncio.get_running_loop().create_future()
        self.phase = "waiting_code"
        try:
            return await asyncio.wait_for(self.code_waiter, 180)
        finally:
            self.code_waiter = None

    async def get_password(self, hint: str | None = None) -> str:
        self.password_waiter = asyncio.get_running_loop().create_future()
        self.phase = "waiting_password"
        self.detail = (hint or "")[:120]
        try:
            return await asyncio.wait_for(self.password_waiter, 180)
        finally:
            self.password_waiter = None
            self.detail = ""

    def provide_code(self, code: str) -> None:
        if not re.fullmatch(r"[0-9]{4,10}", code):
            raise ValueError("invalid SMS code format")
        if self.phase != "waiting_code" or self.code_waiter is None or self.code_waiter.done():
            raise RuntimeError("MAX did not request an SMS code")
        self.phase = "verifying_code"
        self.code_waiter.set_result(code)

    def provide_password(self, password: str) -> None:
        if not 1 <= len(password) <= 128:
            raise ValueError("invalid 2FA password length")
        if self.phase != "waiting_password" or self.password_waiter is None or self.password_waiter.done():
            raise RuntimeError("MAX did not request an additional password")
        self.phase = "verifying_password"
        self.password_waiter.set_result(password)

    async def _login(self, phone: str) -> None:
        from pymax import Client, SmsAuthFlow
        from bridge.tcp_sessions import SessionRunner

        client = None
        runner = None
        active_chat: list[PyMaxHistoryChat | None] = [None]
        try:
            self.dir.mkdir(parents=True, exist_ok=True, mode=0o700)
            if os.name == "posix":
                self.dir.chmod(0o700)
            client = Client(
                phone=phone,
                work_dir=str(self.dir),
                session_name="max-phone.db",
                auth_flow=SmsAuthFlow(self, password_provider=self),
            )
            # Subscribe BEFORE connecting, so the server receives live updates
            # without hitting MAX's fetch_history noninteractive rate limit.
            @client.on_message()
            async def on_max_message(message: Any, _client: Any) -> None:
                adapter = active_chat[0]
                if adapter is not None:
                    adapter.accept_event(message)

            self.phase = "connecting"
            await client.connect()
            self._client = client
            # Persist phone only after successful authorization. Never persist
            # the SMS code or 2FA password in our own files.
            path = self.dir / "phone.txt"
            path.write_text(phone, encoding="ascii")
            if os.name == "posix":
                path.chmod(0o600)
            self.phase = "initializing_chat"
            # The legacy hardcoded chat belongs to a different account.
            # Never fail an authorized session because of that old ID.
            options = await client.fetch_chats()
            self.chat_options = sorted([
                {"id": int(chat.id), "title": str(chat.title or "Диалог")[:72],
                 "type": str(getattr(chat.type, "value", chat.type))}
                for chat in options
                if isinstance(chat.id, int) and chat.id != 0
                and str(getattr(chat.type, "value", chat.type)) in ("DIALOG", "CHAT")
            ], key=lambda item: ("избран" not in item["title"].lower(), item["title"].lower()))
            if not self.chat_options:
                raise ValueError("MAX account has no selectable chats")
            stored = self.dir / "chat-id.txt"
            try:
                saved_id = int(stored.read_text(encoding="ascii").strip())
            except (OSError, ValueError):
                saved_id = 0
            if saved_id not in {chat["id"] for chat in self.chat_options}:
                self.chat_ready = asyncio.Event()
                self.phase = "chat_selection_required"
                # Keep MAX authenticated while the owner selects a chat.
                await self.chat_ready.wait()
            self.phase = "initializing_chat"
            # Selection already validated against fresh fetch_chats() results.
            # Re-querying get_chat() here can fail even for synced dialogs.
            if self.chat_ready:
                stored.write_text(str(self.chat_id), encoding="ascii")
                if os.name == "posix":
                    stored.chmod(0o600)
            else:
                self.chat_id = saved_id
            self.chat_ready = None
            adapter = PyMaxHistoryChat(
                client, self.chat_id, asyncio.get_running_loop(), event_mode=True)
            transport = MaxMessageTransport(adapter, role="host")
            # on_message emits live events; replaying history is neither
            # necessary nor safe: MAX rate-limits frequent history requests.
            active_chat[0] = adapter
            runner = SessionRunner()
            worker = SessionPCWorker(transport, read_max_transport_key(), runner)
            self.phase = "connected"
            print("MAX_PHONE_SESSION_EGRESS_READY", flush=True)
            while True:
                try:
                    await asyncio.to_thread(worker.process_sync)
                except Exception:
                    # MAX reconnect may fail due to vendor API changes.
                    self.phase = "transport_error"
                    self.detail = "MAX message transport disconnected"
                    break
                await asyncio.sleep(2)
        except asyncio.CancelledError:
            raise
        except TimeoutError:
            self.phase = "error"
            self.detail = "SMS or password confirmation expired; request a fresh code"
        except Exception as exc:
            if self._client is not None:
                self.phase = "transport_error"
                self.detail = "MAX login succeeded, but dedicated chat is unavailable"
            else:
                self.phase = "error"
                # Do not echo vendor errors that might contain a token, phone or code.
                self.detail = "MAX authorization failed: " + exc.__class__.__name__
        finally:
            if runner is not None:
                await asyncio.to_thread(runner.stop)
            if client is not None:
                try:
                    await client.close()
                except Exception:
                    pass
            self._client = None
            self.chat_ready = None
            self.chat_options = []

    async def resume_saved(self) -> None:
        path = self.dir / "phone.txt"
        if not path.exists():
            return
        try:
            value = path.read_text(encoding="ascii").strip()
            if PHONE_PATTERN.fullmatch(value):
                await self.begin(value)
        except Exception:
            self.phase = "error"
            self.detail = "saved MAX session unavailable"


