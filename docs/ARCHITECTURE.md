# Design and security gates

**One-account design:** each customer signs into the *same personal MAX account* on the PC host (website/companion) and on their own Android phone. Two MAX accounts are not required. The two app sessions see the same authorized conversation history. `mobile -> host` requests and `host -> mobile` responses are routed by their endpoint roles, plus a request identifier and an encrypted payload. No third-party account is involved.

Planned data path (not deployed): Android VpnService -> phone MAX session -> synchronized messages in that user's MAX account -> PC companion MAX session -> PC internet connection -> return messages -> phone. Both endpoint apps and a supported personal-account integration are still needed.

**Product scope:** designed to restore access to the ordinary internet under allowlist-only connectivity conditions. It is not advertised as a general unblocker, and access to otherwise blocked resources is not promised. This is a capability statement, not an engineering requirement to recreate or enforce external network blocks. Do not advertise a functioning VPN until actual end-to-end tests pass.

The public MAX developer API documented at https://dev.max.ru/docs-api is a **bot API**; its bot token is not a third-party personal-user login token. MAX personal Web automation in Mask0FDark/max-automation is not an official delegated sign-in for arbitrary users. The website and Android app cannot claim account login merely because an existing authorized PC browser session was tested. Never collect personal MAX passwords, SMS codes, browser cookies or profiles on this site.

The `site/` is a self-contained static frontend.

Before enabling VPN: verify bidirectional MAX transport during allowlist conditions; account isolation; authentication; encryption; relay capacity; traffic filtering; safe logging; rate limits; Android VpnService packet pipeline; build/release tests.
