import unittest
from bridge.max_rpc import MaxMessageTransport, make_envelope, parse_envelope

class FakeChat:
    def __init__(self):
        self.messages = []
    def send_text(self, key, action, text):
        assert key == "debug" and action == "send_debug"
        self.messages.append(text)
    def read_debug_messages(self, limit=50):
        return self.messages[-limit:]

class RPC(unittest.TestCase):
    def test_envelope(self):
        data = make_envelope("request", bytes(range(256)))
        result = parse_envelope(data)
        self.assertEqual(result["payload"], bytes(range(256)))
    def test_roundtrip(self):
        chat = FakeChat()
        a, b = MaxMessageTransport(chat), MaxMessageTransport(chat)
        rid = a.send("request", b"ping")
        received = b.receive()
        self.assertEqual(len(received), 1)
        self.assertEqual(received[0]["payload"], b"ping")
        b.send("response", b"pong", rid)
        self.assertEqual(a.await_reply(rid, 1), b"pong")
    def test_fragmented(self):
        chat = FakeChat()
        a, b = MaxMessageTransport(chat), MaxMessageTransport(chat)
        data = bytes(range(256)) * 15
        a.send("request", data)
        self.assertEqual(b.receive()[0]["payload"], data)
        self.assertEqual(b.receive(), [])
    def test_bad_envelope(self):
        with self.assertRaises(ValueError):
            parse_envelope(b"{}")

if __name__ == "__main__":
    unittest.main()
