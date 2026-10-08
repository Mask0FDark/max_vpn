"""Both endpoints use ONE MAX account and the same synchronized message history."""
import unittest
from unittest.mock import patch

from bridge.max_rpc import MaxMessageTransport, make_envelope, parse_envelope
from bridge.pc_client import PCClient
from bridge.pc_worker import PCWorker

TEST_KEY = b"private-local-test-key-at-least-32-bytes"

class SharedAccountChat:
    """Same message history as seen on phone and on web for one MAX account."""
    def __init__(self):
        self.history = []

    def send_text(self, chat_key, action, message):
        assert (chat_key, action) == ("debug", "send_debug")
        self.history.append(message)

    def read_debug_messages(self, limit=50):
        return self.history[-limit:]


class SameAccountTests(unittest.TestCase):
    def test_roles_are_required_in_role_aware_mode(self):
        for role in ("mobile", "host"):
            self.assertIsNotNone(MaxMessageTransport(SharedAccountChat(), role=role))
        with self.assertRaises(ValueError):
            MaxMessageTransport(SharedAccountChat(), role="another-account")
        with self.assertRaises(ValueError):
            make_envelope("request", b"x", sender="host", recipient="host")

    def test_both_devices_ignore_their_own_messages(self):
        chat = SharedAccountChat()
        phone = MaxMessageTransport(chat, role="mobile")
        host = MaxMessageTransport(chat, role="host")
        rid = phone.send("request", b"ping")
        self.assertEqual(phone.receive(), [])
        request = host.receive()
        self.assertEqual(len(request), 1)
        self.assertEqual(request[0]["request_id"], rid)
        self.assertEqual(request[0]["sender"], "mobile")
        self.assertEqual(request[0]["recipient"], "host")
        host.send("response", b"pong", rid)
        self.assertEqual(host.receive(), [])
        reply = phone.receive()
        self.assertEqual(len(reply), 1)
        self.assertEqual(reply[0]["payload"], b"pong")
        self.assertEqual(phone.receive(), [])
        self.assertEqual(host.receive(), [])

    def test_unrouted_legacy_messages_ignored(self):
        chat = SharedAccountChat()
        legacy = MaxMessageTransport(chat)
        host = MaxMessageTransport(chat, role="host")
        legacy.send("request", b"legacy")
        self.assertEqual(host.receive(), [])

    def test_encrypt_authenticated_response_one_account(self):
        chat = SharedAccountChat()
        phone_transport = MaxMessageTransport(chat, role="mobile")
        host_transport = MaxMessageTransport(chat, role="host")
        client = PCClient(phone_transport, TEST_KEY)
        host = PCWorker(host_transport, TEST_KEY)
        with patch("bridge.pc_worker.execute_request", return_value=b"response"):
            # Encrypted client request, handled on the host from the same inbox.
            from bridge.pc_worker import wrap, unwrap
            from bridge.tcp_exchange import request_payload
            rid = "a" * 32
            phone_transport.send("request", wrap(TEST_KEY, rid, request_payload("localhost", 80, b"hello")), rid)
            self.assertEqual(host.process_sync(), 1)
            self.assertEqual(host.process_sync(), 0)
            responses = phone_transport.receive()
            self.assertEqual(len(responses), 1)
            self.assertEqual(unwrap(TEST_KEY, rid, responses[0]["payload"]), b"response")
