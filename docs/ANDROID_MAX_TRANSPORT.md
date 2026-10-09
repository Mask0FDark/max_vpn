# MAX message transport for Android: 0.4.0 alpha

**Direct VPS HTTPS is NOT used when selecting the MAX button.**
`MaxMessageVpnService` starts only after completing a real encrypted
request/response via a dedicated MAX chat with the VPS's PyMax WebClient.

## Protocol

- Android AES-256-GCM packets marked MX2, VPS Python PCWorker dynamically
  decrypts MX1/ChaCha20 or MX2/AES-GCM and uses matching reply scheme.
- Pairing key material derived from private VPS relay token with domain
  separation and SHA256, then SHA256 again for AEAD key compatibility.
- Same `M0FD-TUNNEL-V1:` chunked frames, envelope roles mobile/host.
- Commands TCP open/write/read/close; UDP single DNS exchange to public port 53.
- MAX WebView uses the official site and local saved browser session. No
  credential interception, no fake login screens or cookie extraction.
- Android MUST have a valid MAX WebView session and the VPS MUST be signed
  in to the same MAX account, able to access the dedicated test chat.

## Safety limits

Before TUN creation the Android service:
1. Verifies a local private pairing key exists.
2. Waits for MAX Web chat composer and the chosen test chat.
3. Sends an encrypted MAX message to ask the VPS to open/close an external
   TCP connection and waits for the authenticated encrypted response.
4. Only then binds SOCKS5 and Android VpnService. On failure, no TUN.

## Not yet verified

- Real user authorization to PyMax VPS. QR link requested, server waiting
  for confirmation in official MAX app (do not collect SMS/2FA).
- Real Android WebView sending/reading MAX frames to the actual VPS instance.
- Reliability and performance of data-plane across a physical Android phone.
- MAX latency/rate limits may make sustained internet access unusable; a
  successful emulator or protocol test alone cannot prove otherwise.

Please don't label this a production VPN or release it as one until a
physical-device MAX-chat handshake and real browsing pass.
