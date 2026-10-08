import unittest
from bridge.max_debug import extract_frame

class AdapterTests(unittest.TestCase):
    def test_extract_message(self):
        self.assertEqual(extract_frame("header\nM0FD-TUNNEL-V1:{\"id\":\"test\"}\nfooter"), 'M0FD-TUNNEL-V1:{"id":"test"}')
    def test_ignore_other_message(self):
        self.assertIsNone(extract_frame("ordinary chat text"))
