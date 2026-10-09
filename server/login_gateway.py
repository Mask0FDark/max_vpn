"""Paired-device HTTPS gateway to the private MAX login service.

A paired Android device submits only its own phone/SMS/2FA fields.
The MAX login worker itself has no public Docker port.
"""
from __future__ import annotations

import hmac
import json
import os

import httpx
from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import JSONResponse

router = APIRouter()
STEPS = frozenset(("phone", "code", "password"))
BACKEND = "http://maxvpn-max-egress:8765/internal/max/"


def require_device(token: str | None) -> None:
    valid = os.environ.get("MAXVPN_RELAY_TOKEN", "")
    if len(valid) < 40 or token is None or not hmac.compare_digest(valid, token):
        raise HTTPException(status_code=403, detail="device_not_paired")


async def proxy_to_max(step: str, *, token: str, content: dict | None = None):
    async with httpx.AsyncClient(timeout=15, trust_env=False) as client:
        try:
            headers = {"X-MAXVPN-Token": token}
            if content is None:
                response = await client.get(BACKEND + step, headers=headers)
            else:
                response = await client.post(BACKEND + step, headers=headers, json=content)
            result = response.json()
            return JSONResponse(result, status_code=response.status_code, headers={"Cache-Control":"no-store"})
        except (httpx.RequestError, ValueError):
            raise HTTPException(status_code=503, detail="MAX_authentication_service_unavailable")


@router.get("/api/max/login/status")
async def login_status(x_maxvpn_token: str | None = Header(default=None)):
    require_device(x_maxvpn_token)
    return await proxy_to_max("status", token=x_maxvpn_token)


@router.post("/api/max/login/{step}")
async def login_step(step: str, request: Request,
                     x_maxvpn_token: str | None = Header(default=None)):
    require_device(x_maxvpn_token)
    if step not in STEPS:
        raise HTTPException(status_code=404, detail="not_found")
    # Require JSON, cap size; no MAX code/password may appear in logs or URLs.
    if request.headers.get("content-type", "").split(";")[0].strip() != "application/json":
        raise HTTPException(status_code=415, detail="JSON_required")
    if int(request.headers.get("content-length","0")) > 2048:
        raise HTTPException(status_code=413, detail="payload_too_large")
    raw = await request.body()
    if len(raw) > 2048:
        raise HTTPException(status_code=413, detail="payload_too_large")
    try:
        value = json.loads(raw)
    except (ValueError, UnicodeDecodeError):
        raise HTTPException(status_code=422, detail="invalid_JSON")
    if not isinstance(value, dict) or set(value) != {step} or not isinstance(value[step], str):
        raise HTTPException(status_code=422, detail="invalid_login_field")
    return await proxy_to_max(step, token=x_maxvpn_token, content=value)
