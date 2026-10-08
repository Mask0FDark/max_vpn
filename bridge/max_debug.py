"""Optional local diagnostic adapter to the existing MAX automation installation.

Requires a separate installed max_automation package and an authorized technical
profile. This is not a multi-user relay or a production VPN endpoint.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path

from bridge.framing import FrameCollector, encode_frames


def extract_frame(message: str) -> str | None:
    prefix = "M0FD-TUNNEL-V1:"
    pos = message.find(prefix)
    if pos < 0:
        return None
    end = message.find("\n", pos)
    return message[pos:end if end >= 0 else None].strip()


def diagnostic_roundtrip(root: Path, message: bytes, account_key: str = "technical") -> bool:
    from max_automation import AccountRegistry, MaxConfig, MaxWeb

    config = MaxConfig.load(root / "config" / "chats.json")
    config.require("debug", "read_debug")
    config.require("debug", "send_debug")
    accounts = AccountRegistry.load(root / "config" / "accounts.json", root)
    collector = FrameCollector()
    frames = encode_frames(message)
    with MaxWeb(config, accounts.get(account_key), accounts) as client:
        for frame in frames:
            client.send_text("debug", "send_debug", frame)
        observed = client.read_debug_messages(100)
        seen = set()
        for item in observed:
            candidate = extract_frame(item)
            if candidate in frames and candidate not in seen:
                seen.add(candidate)
                result = collector.accept(candidate)
                if result is not None:
                    return result == message
    return False


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--automation-root", type=Path, required=True)
    parser.add_argument("--text", default="MAX VPN diagnostic")
    args = parser.parse_args()
    os.environ.setdefault("NODE_OPTIONS", "--max-old-space-size=1024")
    success = diagnostic_roundtrip(args.automation_root, args.text.encode("utf-8"))
    print("MAX_DIAGNOSTIC_PASS" if success else "MAX_DIAGNOSTIC_FAIL")
    raise SystemExit(0 if success else 1)


if __name__ == "__main__":
    main()
