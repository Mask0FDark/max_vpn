"""Opt-in live MAX Web transport test using an authorized diagnostic chat.

Reproduces stateful TCP HTTP GET over actual MAX messages. Never reads
unrelated chats and does not store MAX credentials. Does not prove VPN routing.
"""
import argparse
import http.server
import secrets
import socketserver
import threading
import time
import uuid
from pathlib import Path

from bridge.max_debug import extract_frame
from bridge.max_rpc import MaxMessageTransport
from bridge.pc_worker import wrap, unwrap
from bridge.session_relay import SessionPCWorker
from bridge.tcp_sessions import SessionManager, SessionRunner, encode_command, decode_result


class LocalHTTP(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def do_GET(self):
        body = b"MAXVPN_HTTP_ORIGIN_OK"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description="MAX Web live HTTP/TCP probe")
    parser.add_argument("--automation-root", type=Path, required=True)
    parser.add_argument("--account", default="technical")
    args = parser.parse_args()

    from max_automation import MaxConfig, AccountRegistry, MaxWeb

    origin = socketserver.ThreadingTCPServer(("127.0.0.1", 0), LocalHTTP)
    origin.daemon_threads = True
    origin_thread = threading.Thread(target=origin.serve_forever, daemon=True)
    origin_thread.start()

    secret = secrets.token_bytes(32)
    runner = SessionRunner(SessionManager(allow_private=True))
    try:
        config = MaxConfig.load(args.automation_root / "config/chats.json")
        config.require("debug", "read_debug")
        config.require("debug", "send_debug")
        accounts = AccountRegistry.load(args.automation_root / "config/accounts.json", args.automation_root)
        with MaxWeb(config, accounts.get(args.account), accounts) as web:
            mobile = MaxMessageTransport(web, role="mobile")
            host = MaxMessageTransport(web, role="host")
            host.seen_frames.update(
                frame for text in web.read_debug_messages(100)
                if (frame := extract_frame(text)))
            worker = SessionPCWorker(host, secret, runner)

            def exchange(cmd: bytes):
                rid = uuid.uuid4().hex
                mobile.send("request", wrap(secret, rid, cmd), rid)
                for attempt in range(7):
                    worker.process_sync()
                    for message in mobile.receive():
                        if message["request_id"] != rid:
                            continue
                        reply = unwrap(secret, rid, message["payload"])
                        if message["kind"] == "error":
                            raise RuntimeError("MAX worker returned a session error")
                        return decode_result(reply)
                    time.sleep(1.5)
                raise TimeoutError("No authenticated MAX response")

            opened = exchange(encode_command("open", host="127.0.0.1", port=origin.server_address[1]))
            session = opened["session"]
            print("MAX_LIVE_HTTP_OPEN_PASS", flush=True)
            try:
                get = b"GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                exchange(encode_command("write", session=session, data=get))
                received = b""
                for _ in range(8):
                    response = exchange(encode_command("read", session=session))
                    received += response.get("data", b"")
                    if b"MAXVPN_HTTP_ORIGIN_OK" in received or response["status"] == "eof":
                        break
                if b"HTTP/1.1 200" not in received or b"MAXVPN_HTTP_ORIGIN_OK" not in received:
                    raise AssertionError("HTTP response missing from MAX relay")
                print("MAX_LIVE_HTTP_GET_200_PASS", flush=True)
            finally:
                try:
                    exchange(encode_command("close", session=session))
                    print("MAX_LIVE_HTTP_CLOSE_PASS", flush=True)
                except RuntimeError:
                    pass
    finally:
        runner.stop()
        origin.shutdown()
        origin.server_close()
        origin_thread.join(2)


if __name__ == "__main__":
    main()
