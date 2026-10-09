"""Private Docker-network-only phone login API; no public ports.

All requests require a constant-time-verified owner device token. Public
HTTPS endpoints proxy here through maxvpn-web with the same authorization.
Never log request bodies or authorization secrets.
"""
from __future__ import annotations

import asyncio
import hmac
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Header
from pydantic import BaseModel, Field

from server.phone_auth import PhoneAuthentication


def require_owner(token: str | None) -> None:
    expected = os.environ.get("MAXVPN_RELAY_TOKEN", "")
    if len(expected) < 40 or token is None or not hmac.compare_digest(token, expected):
        raise HTTPException(403, "owner_authentication_required")


class PhoneInput(BaseModel):
    phone: str = Field(min_length=9, max_length=16)


class CodeInput(BaseModel):
    code: str = Field(min_length=4, max_length=10)


class PasswordInput(BaseModel):
    password: str = Field(min_length=1, max_length=128)


def make_app(controller: PhoneAuthentication) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        await controller.resume_saved()
        yield
        if controller.task and not controller.task.done():
            controller.task.cancel()
            try:
                await controller.task
            except asyncio.CancelledError:
                pass

    app = FastAPI(openapi_url=None, docs_url=None, redoc_url=None, lifespan=lifespan)

    @app.get("/internal/max/status")
    async def status(x_maxvpn_token: str | None = Header(default=None)):
        require_owner(x_maxvpn_token)
        return controller.status()

    @app.post("/internal/max/phone", status_code=202)
    async def phone(body: PhoneInput, x_maxvpn_token: str | None = Header(default=None)):
        require_owner(x_maxvpn_token)
        try:
            await controller.begin(body.phone)
        except ValueError:
            raise HTTPException(422, "invalid_phone_number")
        except RuntimeError:
            raise HTTPException(429, "authorization_in_progress_or_limited")
        return controller.status()

    @app.post("/internal/max/code")
    async def code(body: CodeInput, x_maxvpn_token: str | None = Header(default=None)):
        require_owner(x_maxvpn_token)
        try:
            controller.provide_code(body.code)
        except (ValueError, RuntimeError):
            raise HTTPException(409, "code_not_requested_or_invalid")
        return controller.status()

    @app.post("/internal/max/password")
    async def password(body: PasswordInput, x_maxvpn_token: str | None = Header(default=None)):
        require_owner(x_maxvpn_token)
        try:
            controller.provide_password(body.password)
        except (ValueError, RuntimeError):
            raise HTTPException(409, "password_not_requested_or_invalid")
        return controller.status()

    return app


controller = PhoneAuthentication(
    session_dir=Path(os.environ.get("MAXVPN_SESSION_DIR", "/sessions")),
    chat_id=int(os.environ.get("MAXVPN_CHAT_ID", "0")),
)
app = make_app(controller)
