import json
import os
import shutil
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from unittest import mock

from werkzeug.security import generate_password_hash

import app as app_module
import recipe_sync

PASSWORD = "correct horse"
PROXY_HTTPS = {"X-Forwarded-Proto": "https", "X-Forwarded-Host": "my-pc.tail.ts.net",
               "X-Forwarded-For": "100.64.0.9"}


class RemoteCase(unittest.TestCase):
    behind_proxy = None

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        (self.tmp / "recipes").mkdir()
        recipe_sync.sync_recipes(self.tmp / "recipes", self.tmp / "db")
        self.app = app_module.create_app(
            self.tmp / "recipes", self.tmp / "db",
            secret_key="test", password_hash=generate_password_hash(PASSWORD),
            behind_proxy=self.behind_proxy,
        )
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def login(self, password=PASSWORD, headers=None, **extra):
        return self.client.post("/login", data={"password": password, **extra}, headers=headers or {})

    @staticmethod
    def session_cookie(response):
        return next(h for h in response.headers.getlist("Set-Cookie") if h.startswith("session="))


class TestBehindProxy(RemoteCase):
    behind_proxy = True

    def test_forwarded_headers_shape_the_request_and_cookies(self):
        import flask
        with self.client:
            response = self.login(headers=PROXY_HTTPS)
            self.assertEqual(response.status_code, 302)
            self.assertTrue(flask.request.is_secure)
            self.assertEqual(flask.request.host, "my-pc.tail.ts.net")
            self.assertEqual(flask.request.remote_addr, "100.64.0.9")
            self.assertEqual(flask.url_for("index", _external=True), "https://my-pc.tail.ts.net/")
        self.assertIn("Secure", self.session_cookie(response))

    def test_plain_lan_request_still_gets_a_usable_cookie(self):
        response = self.login()
        self.assertEqual(response.status_code, 302)
        self.assertNotIn("Secure", self.session_cookie(response))

    def test_hsts_only_over_https(self):
        self.assertIn("Strict-Transport-Security", self.client.get("/login", headers=PROXY_HTTPS).headers)
        self.assertNotIn("Strict-Transport-Security", self.client.get("/login").headers)

    def test_throttle_keys_on_the_forwarded_client_address(self):
        for _ in range(app_module.LOGIN_MAX_FAILURES):
            self.login("nope", headers=PROXY_HTTPS)
        self.assertEqual(self.login(headers=PROXY_HTTPS).status_code, 429)
        other = dict(PROXY_HTTPS, **{"X-Forwarded-For": "100.64.0.10"})
        self.assertEqual(self.login(headers=other).status_code, 302)


class TestNotBehindProxy(RemoteCase):
    behind_proxy = False

    def test_forwarded_headers_are_ignored_by_default(self):
        import flask
        with self.client:
            response = self.login(headers=PROXY_HTTPS)
            self.assertEqual(response.status_code, 302)
            self.assertFalse(flask.request.is_secure)
            self.assertNotEqual(flask.request.host, "my-pc.tail.ts.net")
            self.assertNotEqual(flask.request.remote_addr, "100.64.0.9")
        self.assertNotIn("Secure", self.session_cookie(response))
        self.assertNotIn("Strict-Transport-Security", response.headers)

    def test_env_var_turns_it_on(self):
        with mock.patch.dict(os.environ, {app_module.BEHIND_PROXY_ENV: "1"}):
            app = app_module.create_app(self.tmp / "recipes", self.tmp / "db", secret_key="t")
        self.assertTrue(app.config["BEHIND_PROXY"])
        app = app_module.create_app(self.tmp / "recipes", self.tmp / "db", secret_key="t")
        self.assertFalse(app.config["BEHIND_PROXY"])


class TestLoginThrottle(RemoteCase):
    def test_locks_out_after_max_failures_and_clears_on_success(self):
        for _ in range(app_module.LOGIN_MAX_FAILURES):
            self.assertEqual(self.login("nope").status_code, 401)
        blocked = self.login()  # right password, but locked out
        self.assertEqual(blocked.status_code, 429)
        self.assertIn("Too many attempts", blocked.get_data(as_text=True))
        self.assertNotIn("Set-Cookie", blocked.headers)

    def test_window_expires(self):
        throttle = self.app.extensions["login_throttle"]
        start = datetime(2026, 9, 17, 12, 0)
        for _ in range(app_module.LOGIN_MAX_FAILURES):
            throttle.record_failure("1.2.3.4", now=start)
        self.assertIsNotNone(throttle.retry_after("1.2.3.4", now=start + timedelta(minutes=14)))
        self.assertIsNone(throttle.retry_after("1.2.3.4", now=start + app_module.LOGIN_WINDOW))

    def test_success_resets_the_counter(self):
        for _ in range(app_module.LOGIN_MAX_FAILURES - 1):
            self.login("nope")
        self.assertEqual(self.login().status_code, 302)
        self.client.post("/logout")
        for _ in range(app_module.LOGIN_MAX_FAILURES - 1):
            self.login("nope")
        self.assertEqual(self.login().status_code, 302, "a success must clear earlier failures")


class TestServeOptions(unittest.TestCase):
    """Waitress drops X-Forwarded-* from proxies it doesn't trust, so
    ProxyFix alone is not enough when the app is served for real."""

    def test_nothing_extra_when_not_behind_a_proxy(self):
        self.assertEqual(app_module.serve_options(False), {})

    def test_trusts_the_local_proxy_and_the_headers_proxyfix_needs(self):
        opts = app_module.serve_options(True)
        self.assertEqual(opts["trusted_proxy"], "127.0.0.1")
        self.assertEqual(opts["trusted_proxy_count"], 1)
        self.assertTrue(opts["clear_untrusted_proxy_headers"])
        self.assertEqual(opts["trusted_proxy_headers"],
                         {"x-forwarded-for", "x-forwarded-proto", "x-forwarded-host"})

    def test_proxy_address_can_be_overridden(self):
        self.assertEqual(app_module.serve_options(True, "10.0.0.5")["trusted_proxy"], "10.0.0.5")
        with mock.patch.dict(os.environ, {app_module.PROXY_IP_ENV: "10.0.0.6"}):
            self.assertEqual(app_module.serve_options(True)["trusted_proxy"], "10.0.0.6")

    def test_waitress_accepts_them(self):
        from waitress.adjustments import Adjustments
        names = {name for name, *_ in Adjustments._params}
        for key in app_module.serve_options(True):
            self.assertIn(key, names, f"waitress has no {key} setting")


class TestPublicEndpoints(RemoteCase):
    def test_healthz_needs_no_session_and_reveals_nothing(self):
        response = self.client.get("/healthz")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json(), {"ok": True})

    def test_manifest_is_public_and_points_at_the_shopping_list(self):
        response = self.client.get("/manifest.webmanifest")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.mimetype, "application/manifest+json")
        body = json.loads(response.get_data(as_text=True))
        self.assertEqual(body["start_url"], "/shopping-list")
        self.assertEqual(body["display"], "standalone")
        self.assertEqual({i["sizes"] for i in body["icons"]}, {"192x192", "512x512"})
        for icon in body["icons"]:
            self.assertEqual(self.client.get(icon["src"]).status_code, 200)

    def test_pages_link_the_manifest_and_carry_security_headers(self):
        response = self.client.get("/login")
        body = response.get_data(as_text=True)
        self.assertIn('rel="manifest" href="/manifest.webmanifest"', body)
        self.assertIn('rel="apple-touch-icon"', body)
        self.assertEqual(response.headers["X-Content-Type-Options"], "nosniff")
        self.assertEqual(response.headers["X-Frame-Options"], "DENY")
        self.assertEqual(response.headers["Referrer-Policy"], "same-origin")

    def test_everything_else_is_still_gated(self):
        self.assertEqual(self.client.get("/recipes").status_code, 302)


if __name__ == "__main__":
    unittest.main()
