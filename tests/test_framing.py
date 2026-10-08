import unittest
from bridge.framing import encode_frames, FrameCollector, PREFIX


class TransportTest(unittest.TestCase):
    def test_empty(self):
        frames = encode_frames(b'')
        self.assertEqual(FrameCollector().accept(frames[0]), b'')

    def test_binary_roundtrip_out_of_order(self):
        data = bytes(range(256)) * 24
        frames = encode_frames(data)
        self.assertGreater(len(frames), 1)
        collector = FrameCollector()
        output = None
        for frame in reversed(frames):
            result = collector.accept(frame)
            if result is not None:
                output = result
        self.assertEqual(output, data)

    def test_duplicate(self):
        frames = encode_frames(b'x' * 2000)
        collector = FrameCollector()
        self.assertIsNone(collector.accept(frames[0]))
        self.assertIsNone(collector.accept(frames[0]))
        self.assertEqual(collector.accept(frames[1]), b'x' * 2000)

    def test_reject_garbage(self):
        with self.assertRaises(ValueError):
            FrameCollector().accept(PREFIX + '{}')

    def test_reject_malformed_base64(self):
        import json
        frame = encode_frames(b'diagnostic')[0]
        body = json.loads(frame[len(PREFIX):])
        body['data'] = '!invalid base64!'
        with self.assertRaises(ValueError):
            FrameCollector().accept(PREFIX + json.dumps(body))

    def test_reject_oversized_packet(self):
        with self.assertRaises(ValueError):
            encode_frames(b'x' * (1200 * 257))


if __name__ == '__main__':
    unittest.main()

