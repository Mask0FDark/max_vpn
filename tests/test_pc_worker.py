"""Tests for authenticated PC worker without real accounts or remote MAX."""
import unittest
from unittest.mock import patch

from bridge.max_rpc import MaxMessageTransport
from bridge.pc_worker import PCWorker, unwrap, wrap

KEY = b"a-test-only-shared-secret-at-least-32-bytes"

class FakeChat:
    def __init__(self):
        self.messages = []
    def send_text(self, key, action, value):
        self.messages.append(value)
    def read_debug_messages(self, limit=100):
        return self.messages[-limit:]

class WorkerTests(unittest.IsolatedAsyncioTestCase):
    def test_authentication(self):
        self.assertEqual(unwrap(KEY, "0"*32, wrap(KEY, "0"*32, b"hello")), b"hello")
        with self.assertRaises(ValueError):
            unwrap(KEY, "1"*32, wrap(KEY, "0"*32, b"hello"))
        with self.assertRaises(ValueError):
            unwrap(KEY, "0"*32, b"bad")
        encrypted = wrap(KEY, "0"*32, b"private message")
        self.assertNotIn(b"private message", encrypted)
        with self.assertRaises(ValueError):
            unwrap(KEY, "0"*32, encrypted[:-1] + bytes([encrypted[-1] ^ 1]))

    async def test_authenticated_request_and_no_replay(self):
        chat = FakeChat()
        requester = MaxMessageTransport(chat)
        worker_transport = MaxMessageTransport(chat)
        request_id = "a" * 32
        requester.send("request", wrap(KEY, request_id, b"test data"), request_id)
        worker = PCWorker(worker_transport, KEY)
        with patch("bridge.pc_worker.execute_request", return_value=b"test reply"):
            self.assertEqual(await worker.process(), 1)
            self.assertEqual(await worker.process(), 0)
        responses = [m for m in requester.receive() if m["kind"]=="response"]
        self.assertEqual(len(responses), 1)
        self.assertEqual(unwrap(KEY, request_id, responses[0]["payload"]), b"test reply")

    def test_sync_worker_request(self):
        chat = FakeChat()
        rid = 'f' * 32
        MaxMessageTransport(chat).send('request', wrap(KEY, rid, b'hello'), rid)
        worker = PCWorker(MaxMessageTransport(chat), KEY)
        with patch('bridge.pc_worker.execute_request', return_value=b'OK'):
            self.assertEqual(worker.process_sync(), 1)
            self.assertEqual(worker.process_sync(), 0)
        replies = [m for m in MaxMessageTransport(chat).receive() if m['kind'] == 'response']
        self.assertEqual(unwrap(KEY, rid, replies[0]['payload']), b'OK')

    async def test_unsigned_request_ignored(self):
        chat = FakeChat()
        MaxMessageTransport(chat).send("request", b"unauthorized", "b"*32)
        worker = PCWorker(MaxMessageTransport(chat), KEY)
        self.assertEqual(await worker.process(), 0)
