import unittest
from bridge.max_rpc import MaxMessageTransport
from bridge.pc_worker import PCWorker, wrap, unwrap
from bridge.session_relay import SessionPCWorker
from bridge.tcp_sessions import decode_result, encode_command, SessionRunner, SessionManager
from tests.test_tcp_sessions import SharedChat

KEY = b"mobile-aes-gcm-shared-test-secret-of-40bytes"

class MobileCipherTests(unittest.TestCase):
    def test_aes_gcm_and_original_chacha_replies(self):
        for algo,header in (("aes",b"MX2"),("chacha",b"MX1")):
            data=wrap(KEY,"1"*32,b"test",algorithm=algo)
            self.assertTrue(data.startswith(header))
            self.assertEqual(unwrap(KEY,"1"*32,data),b"test")
            with self.assertRaises(ValueError):
                unwrap(KEY,"2"*32,data)

    def test_host_reply_uses_request_cipher(self):
        from bridge.max_rpc import MaxMessageTransport
        chat=SharedChat()
        client=MaxMessageTransport(chat,role="mobile")
        host=MaxMessageTransport(chat,role="host")
        rid="a"*32
        client.send("request",wrap(KEY,rid,b"ping",algorithm="aes"),rid)
        worker=PCWorker(host,KEY)
        pending=worker._pending()
        self.assertEqual(pending,[(rid,b"ping")])
        worker._send_result(rid,b"pong")
        responses=client.receive()
        self.assertEqual(len(responses),1)
        self.assertEqual(responses[0]["kind"],"response")
        self.assertTrue(responses[0]["payload"].startswith(b"MX2"))
        self.assertEqual(unwrap(KEY,rid,responses[0]["payload"]),b"pong")

if __name__=="__main__":
    unittest.main()
