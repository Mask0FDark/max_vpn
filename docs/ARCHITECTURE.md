# Design and security gates

Architecture is planned, not deployed: Android VpnService -> authenticated per-user MAX message transport -> isolated bridge -> constrained egress -> reply. Each user must use their **own MAX account**.

**Product scope:** designed to restore access to the ordinary internet under allowlist-only connectivity conditions. It is not advertised as a general unblocker, and access to otherwise blocked resources is not promised. This is a capability statement, not an engineering requirement to recreate or enforce external network blocks. Do not advertise a functioning VPN until actual end-to-end tests pass.

MAX personal Web automation in Mask0FDark/max-automation is not an official delegated sign-in. Never collect a user's password, SMS code, session cookie or browser profile on this site. Evaluate supported account delegation before adding login.

The `site/` is standalone and never imports VGA Stream's Django app, user database or deployment files. Its hostname may be a subdomain of vga-stream.ru without sharing an application.

Before enabling VPN: verify bidirectional MAX transport during allowlist conditions; account isolation; authentication; encryption; relay capacity; traffic filtering; safe logging; rate limits; Android VpnService packet pipeline; build/release tests.
