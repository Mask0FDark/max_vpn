"""Optional MAX Web live HTTPS test with TLS MemoryBIO over encrypted messages.

Use an already authorized diagnostic MAX profile. Traffic terminates at an
external HTTPS origin; no private keys, passwords, or session cookies are read.
"""
import argparse
import secrets
import ssl
import time
import uuid
from pathlib import Path

from bridge.max_debug import extract_frame
from bridge.max_rpc import MaxMessageTransport
from bridge.pc_worker import wrap, unwrap
from bridge.session_relay import SessionPCWorker
from bridge.tcp_sessions import SessionManager, SessionRunner, encode_command, decode_result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--automation-root", type=Path, required=True)
    parser.add_argument("--account", default="technical")
    parser.add_argument("--target", default="example.com")
    args = parser.parse_args()
    from max_automation import MaxConfig, AccountRegistry, MaxWeb

    key = secrets.token_bytes(32)
    runner = SessionRunner(SessionManager())
    try:
        config = MaxConfig.load(args.automation_root / "config/chats.json")
        config.require("debug", "read_debug")
        config.require("debug", "send_debug")
        accounts = AccountRegistry.load(args.automation_root / "config/accounts.json", args.automation_root)
        with MaxWeb(config, accounts.get(args.account), accounts) as web:
            client = MaxMessageTransport(web, role="mobile")
            host = MaxMessageTransport(web, role="host")
            host.seen_frames.update(
                frame for text in web.read_debug_messages(100)
                if (frame := extract_frame(text)))
            worker = SessionPCWorker(host, key, runner)

            def exchange(cmd):
                rid = uuid.uuid4().hex
                client.send("request", wrap(key, rid, cmd), rid)
                for _ in range(7):
                    worker.process_sync()
                    for entry in client.receive():
                        if entry["request_id"] != rid:
                            continue
                        reply = unwrap(key, rid, entry["payload"])
                        if entry["kind"] == "error":
                            raise RuntimeError("MAX worker returned a session error")
                        return decode_result(reply)
                    time.sleep(1)
                raise TimeoutError("No authenticated MAX response")

            sid = exchange(encode_command("open", host=args.target, port=443))["session"]
            print("MAX_LIVE_HTTPS_OPEN_PASS", flush=True)
            try:
                context = ssl.create_default_context()
                input_bio, output_bio = ssl.MemoryBIO(), ssl.MemoryBIO()
                tls = context.wrap_bio(input_bio, output_bio, server_hostname=args.target)

                def flush():
                    while output_bio.pending:
                        piece = output_bio.read(2048)
                        exchange(encode_command("write", session=sid, data=piece))

                established = False
                for _ in range(18):
                    try:
                        tls.do_handshake()
                        established = True
                    except ssl.SSLWantReadError:
                        pass
                    flush()
                    if established:
                        break
                    packet = exchange(encode_command("read", session=sid))
                    if packet["status"] == "eof":
                        raise RuntimeError("remote server closed during TLS handshake")
                    if packet["data"]:
                        input_bio.write(packet["data"])
                if not established:
                    raise TimeoutError("TLS handshake did not complete")
                print("MAX_LIVE_HTTPS_TLS_VERIFIED", flush=True)

                tls.write(f"GET / HTTP/1.1\r\nHost: {args.target}\r\nConnection: close\r\n\r\n".encode())
                flush()
                plaintext = b""
                for _ in range(24):
                    try:
                        plaintext += tls.read(8192)
                    except (ssl.SSLWantReadError, ssl.SSLZeroReturnError):
                        pass
                    if b"HTTP/1.1 200" in plaintext and b"\r\n\r\n" in plaintext and len(plaintext) > 150:
                        break
                    flush()
                    packet = exchange(encode_command("read", session=sid))
                    if packet["status"] == "eof":
                        break
                    if packet["data"]:
                        input_bio.write(packet["data"])
                if b"HTTP/1.1 200" not in plaintext:
                    raise AssertionError("No verified HTTP 200 over MAX-encrypted TLS connection")
                print("MAX_LIVE_HTTPS_GET_200_PASS", flush=True)
            finally:
                try:
                    exchange(encode_command("close", session=sid))
                except RuntimeError:
                    pass
    finally:
        runner.stop()


if __name__ == "__main__":
    main()
