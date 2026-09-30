"""The browser sign-in flow in set_gcal_oauth.py.

Google is never contacted. What is exercised is the awkward half: a
one-shot loopback server, the PKCE parameters, the state check, and the
fact that the whole thing actually returns instead of hanging -- which is
the failure that would waste the user's time at the worst moment.
"""
import base64
import hashlib
import sys
import unittest
import unittest.mock
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import gcal
import set_gcal_oauth


class SignInTests(unittest.TestCase):
    def setUp(self):
        set_gcal_oauth._CallbackHandler.result = {}
        # sign_in talks to the user; that is wanted in real use and only
        # noise here, where it would bury the suite's own output.
        quiet = unittest.mock.patch("builtins.print")
        quiet.start()
        self.addCleanup(quiet.stop)

    def browser_that_answers(self, captured, *, code="auth-code", state=None, error=None):
        """Stands in for the user clicking Allow: calls the redirect back."""
        def fake_open(url):
            captured["url"] = url
            query = dict(urllib.parse.parse_qsl(urllib.parse.urlparse(url).query))
            captured["query"] = query
            reply = {"state": state if state is not None else query["state"]}
            if error:
                reply["error"] = error
            else:
                reply["code"] = code
            redirect = query["redirect_uri"] + "?" + urllib.parse.urlencode(reply)
            with urllib.request.urlopen(redirect, timeout=10) as response:
                captured["page"] = response.read()
            return True
        return fake_open

    def test_completes_the_flow_and_returns_a_refresh_token(self):
        captured = {}
        exchanged = {}

        def fake_exchange(payload):
            exchanged.update(payload)
            return {"refresh_token": "1//the-refresh-token", "access_token": "ya29.x"}

        with unittest.mock.patch.object(set_gcal_oauth.webbrowser, "open",
                                        self.browser_that_answers(captured)), \
             unittest.mock.patch.object(set_gcal_oauth, "_exchange", fake_exchange):
            token = set_gcal_oauth.sign_in("client-id", "client-secret")

        self.assertEqual(token, "1//the-refresh-token")
        self.assertIn(b"Signed in", captured["page"])
        self.assertEqual(exchanged["grant_type"], "authorization_code")
        self.assertEqual(exchanged["code"], "auth-code")

    def test_asks_google_for_the_right_things(self):
        captured = {}
        with unittest.mock.patch.object(set_gcal_oauth.webbrowser, "open",
                                        self.browser_that_answers(captured)), \
             unittest.mock.patch.object(set_gcal_oauth, "_exchange",
                                        lambda payload: {"refresh_token": "1//x"}):
            set_gcal_oauth.sign_in("client-id", "client-secret")

        query = captured["query"]
        self.assertEqual(query["code_challenge_method"], "S256")
        # Without both of these Google returns no refresh token on a repeat
        # sign-in, and the link silently lasts one hour.
        self.assertEqual(query["access_type"], "offline")
        self.assertEqual(query["prompt"], "consent")
        self.assertIn("calendar.events", query["scope"])
        self.assertTrue(query["redirect_uri"].startswith("http://127.0.0.1:"),
                        "must come back to this machine only")

    def test_the_pkce_challenge_matches_the_verifier_sent_later(self):
        captured = {}
        exchanged = {}
        with unittest.mock.patch.object(set_gcal_oauth.webbrowser, "open",
                                        self.browser_that_answers(captured)), \
             unittest.mock.patch.object(set_gcal_oauth, "_exchange",
                                        lambda payload: exchanged.update(payload)
                                        or {"refresh_token": "1//x"}):
            set_gcal_oauth.sign_in("client-id", "client-secret")

        expected = base64.urlsafe_b64encode(
            hashlib.sha256(exchanged["code_verifier"].encode("ascii")).digest()
        ).rstrip(b"=").decode("ascii")
        self.assertEqual(captured["query"]["code_challenge"], expected)

    def test_a_mismatched_state_is_refused(self):
        captured = {}
        with unittest.mock.patch.object(set_gcal_oauth.webbrowser, "open",
                                        self.browser_that_answers(captured, state="not-ours")), \
             unittest.mock.patch.object(set_gcal_oauth, "_exchange",
                                        lambda payload: {"refresh_token": "1//x"}):
            with self.assertRaises(gcal.GCalError) as caught:
                set_gcal_oauth.sign_in("client-id", "client-secret")
        self.assertIn("did not match", str(caught.exception))

    def test_a_refused_consent_is_reported(self):
        captured = {}
        with unittest.mock.patch.object(set_gcal_oauth.webbrowser, "open",
                                        self.browser_that_answers(captured, error="access_denied")), \
             unittest.mock.patch.object(set_gcal_oauth, "_exchange",
                                        lambda payload: {"refresh_token": "1//x"}):
            with self.assertRaises(gcal.GCalError) as caught:
                set_gcal_oauth.sign_in("client-id", "client-secret")
        self.assertIn("access_denied", str(caught.exception))

    def test_no_refresh_token_explains_how_to_get_one(self):
        captured = {}
        with unittest.mock.patch.object(set_gcal_oauth.webbrowser, "open",
                                        self.browser_that_answers(captured)), \
             unittest.mock.patch.object(set_gcal_oauth, "_exchange",
                                        lambda payload: {"access_token": "ya29.x"}):
            with self.assertRaises(gcal.GCalError) as caught:
                set_gcal_oauth.sign_in("client-id", "client-secret")
        self.assertIn("myaccount.google.com/permissions", str(caught.exception))


class SignInTimeoutTests(unittest.TestCase):
    def test_nobody_clicking_allow_times_out(self):
        set_gcal_oauth._CallbackHandler.result = {"code": "stale"}
        with unittest.mock.patch("builtins.print"), \
                unittest.mock.patch.object(set_gcal_oauth.webbrowser, "open", lambda url: True):
            with self.assertRaises(gcal.GCalError) as caught:
                set_gcal_oauth.sign_in("client-id", "client-secret", timeout=0.3)
        self.assertIn("timed out waiting for the Google sign-in", str(caught.exception))


class ChooseCalendarTests(unittest.TestCase):
    CALENDARS = [
        {"id": "primary@gmail.com", "summary": "Primary", "primary": True, "accessRole": "owner"},
        {"id": "fam@group.calendar.google.com", "summary": "Family", "accessRole": "writer"},
    ]

    def test_picks_the_one_you_choose(self):
        with unittest.mock.patch("builtins.input", side_effect=["2"]):
            self.assertEqual(set_gcal_oauth.choose_calendar(self.CALENDARS),
                             "fam@group.calendar.google.com")

    def test_keeps_asking_until_the_answer_is_valid(self):
        with unittest.mock.patch("builtins.input", side_effect=["", "9", "x", "1"]):
            self.assertEqual(set_gcal_oauth.choose_calendar(self.CALENDARS),
                             "primary@gmail.com")

    def test_only_writable_calendars_are_offered(self):
        payload = {"items": [
            {"id": "a", "accessRole": "owner"},
            {"id": "b", "accessRole": "reader"},
            {"id": "c", "accessRole": "writer"},
            {"id": "d", "accessRole": "freeBusyReader"},
        ]}

        class Credentials:
            def access_token(self):
                return "tok"

        class Response:
            def read(self):
                import json
                return json.dumps(payload).encode("utf-8")

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        with unittest.mock.patch("urllib.request.urlopen", lambda *a, **k: Response()):
            offered = set_gcal_oauth.list_calendars(Credentials())
        self.assertEqual([item["id"] for item in offered], ["a", "c"])


if __name__ == "__main__":
    unittest.main()
