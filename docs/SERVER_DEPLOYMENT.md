# MAX VPN VPS deployment

The server is independent of VGA Stream, M0D, and other applications. It runs as a dedicated Docker Compose project under `/home/vga/maxvpn`, bound only to `127.0.0.1:18764` behind a standalone nginx virtual host for `max-vpn.mask-0f-darkness.ru`.

## Current functions

- `GET /` public information page
- `GET /health` health probe
- `GET /api/status` truthfully reports `login_available=false` and `vpn_available=false`
- No password/SMS intake. Nothing is stored in other services' databases.

## Re-deploy

```bash
cd /home/vga/maxvpn
git pull --ff-only origin main
docker compose build
docker compose up -d --remove-orphans
docker compose ps
curl -fsS http://127.0.0.1:18764/health
```

Do not expose the container's internal port directly or use this prototype to collect MAX credentials. The username/verification/2FA flow is not enabled pending a secure supported authentication integration.
