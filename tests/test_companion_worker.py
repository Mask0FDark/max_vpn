import unittest
from unittest.mock import Mock, patch

from companion.worker import load_shared_key, run

class CompanionWorkerTests(unittest.TestCase):
    def test_requires_shared_private_key(self):
        with patch.dict("os.environ", {"MAX_VPN_SHARED_SECRET": "too-short"}):
            with self.assertRaises(ValueError):
                load_shared_key()

    def test_worker_uses_local_companion_browser_profile(self):
        browser = Mock()
        browser.read_debug_messages.return_value = []
        browser_cm = Mock()
        browser_cm.__enter__ = Mock(return_value=browser)
        browser_cm.__exit__ = Mock(return_value=False)
        with patch.dict("os.environ", {"MAX_VPN_SHARED_SECRET": "q" * 40}):
            with patch("companion.worker.CompanionMaxChat", return_value=browser_cm) as chat_cls:
                with patch("companion.worker.MaxMessageTransport") as transport_cls:
                    with patch("companion.worker.PCWorker") as worker_cls:
                        worker_cls.return_value.process_sync.side_effect = StopIteration("test stop")
                        with self.assertRaises(StopIteration):
                            run("-321")
                        chat_cls.assert_called_once_with("-321")
                        transport_cls.assert_called_once_with(browser, role="host")

if __name__ == "__main__":
    unittest.main()
