"""Public MAX VPN server entrypoint.

A standalone deployment scaffold, intentionally without password/OTP intake
until a secure supported user authorization method is available.
"""
from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.responses import FileResponse

from server.relay import router as relay_router
from starlette.middleware.trustedhost import TrustedHostMiddleware

SITE = Path(__file__).resolve().parents[1] / "site"

app = FastAPI(title="MAX VPN", docs_url=None, redoc_url=None, openapi_url=None)
app.include_router(relay_router)

@app.middleware("http")
async def security(request: Request, call_next):
    response = await call_next(request)
    response.headers["Cache-Control"] = "no-store"
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; style-src 'self'; script-src 'self'; "
        "object-src 'none'; frame-ancestors 'none'; base-uri 'none'"
    )
    return response


@app.get("/health")
def health():
    return {"service": "maxvpn", "status": "ok"}


@app.get("/api/status")
def status():
    return {"service": "maxvpn", "login_available": False,
            "vpn_available": False, "deployment": "independent"}


@app.get("/")
def index():
    return FileResponse(SITE / "remote.html", media_type="text/html")


@app.get("/style.css")
def style():
    return FileResponse(SITE / "style.css", media_type="text/css")
