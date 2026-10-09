# MAX transport on VPS (opt-in prototype)

This worker is **the MAX message transport**, distinct from the existing
`maxvpn-web` / direct HTTPS relay. It is experimental, not connected to the
Android VpnService yet, and not automatically active.

## Mechanism

- One MAX account, used on the owner's phone and VPS. The VPS uses PyMax
  `WebClient`; initial authorization requires confirmation in the official
  MAX app (QR link from the operator-only container logs). The app's
  authorization session is persisted in a local SQLite file.
- Only a single dedicated chat identified by `MAXVPN_CHAT_ID` can be read
  or written by the worker.
- The encrypted message framing in `bridge/max_rpc.py` and stateful
  TCP commands in `bridge/session_relay.py` are reused. Destinations
  must be public IP addresses. No open proxy on a public port.
- The message AEAD key is derived from the existing private VPS pairing
  token, with separate domain separation; the phone MAX client will need
  the same derivation before these two endpoints can actually connect.

## Deployment

The service is disabled by default with Compose profile `max`. It cannot
launch until the owner has explicitly chosen the dedicated MAX chat and
approved the MAX account session.

```bash
cd /home/vga/maxvpn
# Select the actual chat ID in private .env (do NOT commit)
# MAXVPN_CHAT_ID=...
sudo install -d -o 10001 -g 10001 -m 700 private-data/max-sessions
docker compose --profile max build maxvpn-max-egress
# Only when pairing/permission confirmed:
docker compose --profile max up -d maxvpn-max-egress
docker compose logs --tail=30 maxvpn-max-egress
```

Never publish QR links, session files, pairing tokens, passwords or SMS codes.
PyMax is **not an official MAX API**, may break or violate MAX terms.
The official developer API is for bots and does not replace personal login.

## Blocking checks

- Real VPS authorization with one dedicated MAX account: not yet completed.
- Android must use MAX as its wire transport, instead of the direct WSS
  connection: not yet implemented.
- Independent Android-to-VPS MAX exchange and real VPN packet routing: not
  yet verified.
- Performance: prior single-browser MAX HTTPS proof took about 11s per
  request and is not evidence of usable browsing or whitelist reliability.

Do not advertise or enable this as a production MAX VPN until all above pass.
