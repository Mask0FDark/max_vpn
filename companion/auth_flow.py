"""Local MAX Web sign-in, adapted from max-automation/authorize.py.

The user signs in on the official https://web.max.ru site in an Edge window.
Credentials and cookies remain inside a private persistent browser profile.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

MAX_URL = "https://web.max.ru/"
EDGE_PATHS = (
    Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
    Path(r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"),
)
CHAT_INDICATORS = ("button.cell", '[data-testid="chat-list"]')
LOGIN_INDICATORS = ('input[autocomplete="tel"]', 'input[type="tel"]')


def state_root() -> Path:
    if os.name == "nt":
        return Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "MAXVPN"
    return Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local" / "state"))) / "maxvpn"


def write_state(path: Path, state: str, **other: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {"state": state, "updated_at": datetime.now(timezone.utc).isoformat(), **other}
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    temporary.replace(path)


def classify_login_page(has_chat: bool, has_login_form: bool) -> str:
    if has_chat:
        return "signed_in"
    if has_login_form:
        return "awaiting_sign_in"
    return "checking"


def inspect_page(page: object) -> str:
    try:
        has_chat = any(page.locator(selector).first.is_visible(timeout=800) for selector in CHAT_INDICATORS)
        has_form = any(page.locator(selector).first.is_visible(timeout=800) for selector in LOGIN_INDICATORS)
    except Exception:
        return "checking"
    return classify_login_page(has_chat, has_form)


def open_edge_without_driver(edge: Path, profile: Path, status: Path) -> int:
    """Fallback for Playwright/Node crashes: direct isolated Edge window.

    Manual login on MAX is still possible, but cannot be auto-verified.
    """
    try:
        subprocess.Popen([str(edge), f"--user-data-dir={profile}",
                          "--no-first-run", "--start-maximized", MAX_URL],
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL)
    except OSError:
        write_state(status, "error", error="edge_launch_failed")
        return 1
    write_state(status, "manual_browser_opened", profile_saved=True,
                verification="not_checked")
    return 0


def authorize(root: Path | None = None) -> int:
    from playwright.sync_api import sync_playwright

    root = root or state_root()
    root.mkdir(parents=True, exist_ok=True)
    status = root / "auth-status.json"
    profile = root / "max-web-profile"
    edge = next((path for path in EDGE_PATHS if path.is_file()), None)
    if not edge:
        write_state(status, "error", error="edge_not_found")
        return 1
    write_state(status, "opening")
    signed_in = False
    phase = "playwright_start"
    try:
        with sync_playwright() as playwright:
            phase = "browser_launch"
            context = playwright.chromium.launch_persistent_context(
                user_data_dir=profile,
                executable_path=edge,
                headless=False,
                no_viewport=True,
                args=["--start-maximized"],
            )
            phase = "page_navigation"
            page = context.pages[0] if context.pages else context.new_page()
            page.goto(MAX_URL, wait_until="domcontentloaded", timeout=60_000)
            write_state(status, "awaiting_sign_in")
            phase = "sign_in_monitor"
            while context.pages:
                current = inspect_page(page)
                if current == "signed_in" and not signed_in:
                    signed_in = True
                    write_state(status, "signed_in")
                elif current == "awaiting_sign_in" and signed_in:
                    signed_in = False
                    write_state(status, "awaiting_sign_in")
                time.sleep(1.5)
            write_state(status, "browser_closed", profile_saved=True,
                        previously_signed_in=signed_in)
    except Exception as exc:
        # If Playwright's Node driver fails before Edge loads, direct Edge
        # still permits manual login into a private persistent profile.
        if phase in ("playwright_start", "browser_launch"):
            return open_edge_without_driver(edge, profile, status)
        # Never serialize browser content or session/cookie data.
        write_state(status, "error", error=type(exc).__name__, phase=phase)
        return 1
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Authorize MAX in a local Edge profile")
    parser.add_argument("--state-dir", type=Path)
    arguments = parser.parse_args()
    raise SystemExit(authorize(arguments.state_dir))
