"""One-time server-owner pairing for direct VPS (not MAX account sign-in).

The server owner issues a random 96-bit pairing code over SSH. One client
redeems it over TLS, receives the WSS access token, and stores it encrypted
in Android Keystore. The code is consumed exactly once and expires in 24h.
"""
from __future__ import annotations

import hmac
import json
import os
import re
import secrets
import threading
import time
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

router = APIRouter()
LOCK = threading.Lock()
CODE_TTL_SECONDS = 86400
MAX_ATTEMPTS_PER_IP = 8
WINDOW_SECONDS = 900
_attempts: dict[str, list[float]] = {}


def code_path() -> Path:
    return Path(os.getenv("MAXVPN_PAIRING_FILE", "/app/private-data/pairing.json"))


def issue_code(path: Path | None = None) -> str:
    target = path or code_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    code = secrets.token_hex(12)
    # Replace old one-time code; no pairing code is stored in Git or Docker env.
    tmp = target.with_suffix(".tmp")
    tmp.write_text(json.dumps({"code": code, "created": time.time()}), encoding="ascii")
    tmp.chmod(0o600)
    tmp.replace(target)
    return code


class PairInput(BaseModel):
    code: str = Field(min_length=24, max_length=24, pattern=r"^[0-9a-fA-F]{24}$")


def redeem(code: str, path: Path | None = None, *, now: float | None = None) -> str | None:
    target = path or code_path()
    expected_token = os.getenv("MAXVPN_RELAY_TOKEN", "")
    if len(expected_token) < 40:
        return None
    with LOCK:
        try:
            packet = json.loads(target.read_text(encoding="ascii"))
            issued = float(packet["created"])
            stored = str(packet["code"])
        except (OSError, ValueError, KeyError, TypeError):
            return None
        when = time.time() if now is None else now
        if issued > when + 60 or when - issued > CODE_TTL_SECONDS:
            target.unlink(missing_ok=True)
            return None
        if not hmac.compare_digest(stored, code.lower()):
            return None
        # Consume before returning bearer token to caller.
        target.unlink()
        return expected_token


@router.post("/api/pair")
def pair(body: PairInput, request: Request):
    address = request.headers.get("x-real-ip") or (request.client.host if request.client else "unknown")
    now = time.monotonic()
    with LOCK:
        recent = [t for t in _attempts.get(address, []) if t > now - WINDOW_SECONDS]
        if len(recent) >= MAX_ATTEMPTS_PER_IP:
            raise HTTPException(429, "pairing_attempt_limit")
        recent.append(now)
        _attempts[address] = recent
    token = redeem(body.code)
    if token is None:
        raise HTTPException(403, "invalid_or_expired_pairing_code")
    return {"token": token, "mode": "direct_vps_https"}


if __name__ == "__main__":
    print(issue_code(), flush=True)
