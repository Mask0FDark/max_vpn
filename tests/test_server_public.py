import unittest
from fastapi.testclient import TestClient
from server.public import app

class PublicServerTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    def tearDown(self):
        self.client.close()

    def test_health(self):
        response = self.client.get('/health')
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {'service':'maxvpn','status':'ok'})

    def test_truthful_status(self):
        response = self.client.get('/api/status')
        self.assertEqual(response.status_code, 200)
        self.assertFalse(response.json()['login_available'])
        self.assertFalse(response.json()['vpn_available'])

    def test_page_and_static_style(self):
        page = self.client.get('/')
        self.assertEqual(page.status_code, 200)
        self.assertIn('MAX VPN / ANDROID PREVIEW',page.text)
        self.assertNotIn('type="password"', page.text)
        self.assertEqual(self.client.get('/style.css').status_code,200)

    def test_hardening_headers(self):
        response = self.client.get('/')
        self.assertEqual(response.headers['X-Frame-Options'], 'DENY')
        self.assertEqual(response.headers['Cache-Control'],'no-store')
        self.assertIn("default-src 'self'",response.headers['Content-Security-Policy'])

    def test_unimplemented_login_is_not_exposed(self):
        self.assertEqual(self.client.post('/api/auth/start',json={'phone':'+79990000000'}).status_code,404)

if __name__ == '__main__':
    unittest.main()
