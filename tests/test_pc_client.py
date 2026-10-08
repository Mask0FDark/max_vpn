import unittest
from bridge.pc_client import PCClient
from bridge.pc_worker import unwrap, wrap

KEY = b"local-test-key-with-at-least-thirty-two-bytes"

class StubTransport:
    def __init__(self, kind="response"):
        self.kind = kind
        self.saved = None

    def send(self, kind, payload, request_id):
        self.saved = (kind, payload, request_id)
        return request_id

    def receive(self):
        rid = self.saved[2]
        result = b"OK" if self.kind == "response" else b"denied"
        return [{"kind":self.kind, "request_id":rid, "payload":wrap(KEY,rid,result)}]

class ClientTests(unittest.TestCase):
    def test_request_response(self):
        transport = StubTransport()
        response = PCClient(transport, KEY).exchange("localhost", 3000, b"PING", timeout=1)
        self.assertEqual(response, b"OK")
        kind, data, rid = transport.saved
        self.assertEqual(kind, "request")
        self.assertIn(b"localhost", unwrap(KEY, rid, data))

    def test_error_response(self):
        with self.assertRaisesRegex(RuntimeError, "denied"):
            PCClient(StubTransport("error"), KEY).exchange("localhost", 3000, b"PING", timeout=1)
