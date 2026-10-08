import unittest
from server.public import app

class PublicServerTests(unittest.TestCase):
    def test_health(self):
        import asyncio
        from server.public import health
        self.assertEqual(health(), {'service':'maxvpn', 'status':'ok'})

    def test_safe_status(self):
        from server.public import status
        data=status()
        self.assertFalse(data['login_available'])
        self.assertFalse(data['vpn_available'])

    def test_assets_exist(self):
        from server.public import SITE
        for path in ('remote.html','style.css'):
            self.assertTrue((SITE/path).is_file())
        self.assertNotIn('type="password"',(SITE/'remote.html').read_text(encoding='utf-8'))

if __name__ == '__main__':
    unittest.main()
