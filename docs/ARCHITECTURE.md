# Design and security gates

Architecture is planned, not deployed: Android VpnService -> authenticated per-user MAX message transport -> isolated bridge -> constrained egress -> reply. Each user must use their **own MAX account**.

**Only allowlist restrictions may be bypassed.** A remote egress does not automatically preserve an operator's other restrictions. No connection until independently enforced DNS, domain, IP filtering and fail-closed tests have been implemented; do not claim otherwise.

MAX personal Web automation in Mask0FDark/max-automation is not an official delegated sign-in. Never collect a user's password, SMS code, session cookie or browser profile on this site. Evaluate supported account delegation before adding login.

The `site/` is standalone and never imports VGA Stream's Django app, user database or deployment files. Its hostname may be a subdomain of vga-stream.ru without sharing an application.

Before enabling VPN: verify bidirectional MAX transport during allowlist conditions; account isolation; authentication; encryption; relay capacity; traffic filtering; safe logging; rate limits; Android VpnService packet pipeline; build/release tests.
