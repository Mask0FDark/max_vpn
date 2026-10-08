# PC diagnostic transport (developer preview)

This repository contains an experimental request/response adapter using an
existing MAX Web automation installation. It is **not** a functional VPN, a
system-wide proxy, a production relay, or an Android network connection.

## What has been tested

- A short TCP echo request was sent and received through the real MAX Web
  technical test chat on one authorized account.
- A PC-side request handler accepted an encrypted message, executed the
  short local TCP request, and returned an encrypted response through MAX.
- Separate unit tests cover request framing, client, worker, and a localhost
  TCP echo server.

## PC-only diagnostic setup

Use two **separately authorized browser profiles** that can access the same
consented technical test chat. Do not run multiple Playwright contexts against
the same persistent profile. This diagnostic depends on the separate
`max_automation` installation; account profiles, chat settings, and
credentials are never stored here.

1. Install Python 3.12+ and `pip install -r requirements-test.txt`.
2. Provide `MAX_VPN_SHARED_SECRET` as a randomly generated, private,
   high-entropy secret of at least 32 bytes. Both endpoints need the same key.
   Never publish it or send it through the MAX chat.
3. Set `PYTHONPATH` to include the repository and the separate automation
   installation.
4. Run the PC handler with
   `python -m bridge.pc_worker --automation-root <automation-directory>`.
5. In the other authorized profile, test a bounded request with
   `python -m bridge.pc_client --automation-root <automation-directory> --account technical --host 127.0.0.1 --port <test-port> --text PING`.

This exchanges one short TCP request and one response. Each MAX message may
take seconds to transmit. Continuous TCP, TLS/browser proxying, authentication
of ordinary users, mobile traffic routing, and behavior under allowlist
conditions remain **unimplemented or unverified**.

The shared-key payload encryption uses ChaCha20-Poly1305 with a random 96-bit
nonce and a derived 256-bit key. The protocol is experimental and has not had
an external security audit. The worker must not be exposed as a public service.
