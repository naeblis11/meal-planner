import unittest
from pathlib import Path
from unittest import mock

import gcal
import ha_sync
import set_api_token
import set_gcal
import set_gcal_oauth
import set_ha_link
import set_password
from werkzeug.security import check_password_hash


class PasswordTests(unittest.TestCase):
    def test_sets_a_hash_and_a_key(self):
        values = {}
        set_password.apply_password(values, "pw")
        self.assertTrue(check_password_hash(values[set_password.PASSWORD_HASH_ENV], "pw"))
        self.assertEqual(len(values[set_password.SECRET_KEY_ENV]), 64)

    def test_keeps_the_existing_session_key(self):
        values = {set_password.SECRET_KEY_ENV: "k" * 64}
        set_password.apply_password(values, "pw")
        self.assertEqual(values[set_password.SECRET_KEY_ENV], "k" * 64)


class ApiTokenTests(unittest.TestCase):
    def test_ensure_keeps_an_existing_token(self):
        values = {set_api_token.API_TOKEN_ENV: "abc"}
        self.assertEqual(set_api_token.ensure_api_token(values), "abc")

    def test_ensure_creates_one(self):
        values = {}
        token = set_api_token.ensure_api_token(values)
        self.assertEqual(values[set_api_token.API_TOKEN_ENV], token)
        self.assertEqual(len(token), 64)

    def test_new_always_rotates(self):
        values = {set_api_token.API_TOKEN_ENV: "abc"}
        self.assertNotEqual(set_api_token.new_api_token(values), "abc")


class HALinkTests(unittest.TestCase):
    def test_check_returns_the_item_count(self):
        link = mock.Mock(get_items=mock.Mock(return_value=[{}, {}]))
        count = set_ha_link.check_ha_link("http://ha", "t", "todo.x", make_link=lambda *a: link)
        self.assertEqual(count, 2)

    def test_check_passes_errors_through(self):
        link = mock.Mock(get_items=mock.Mock(side_effect=ha_sync.HAError("401")))
        with self.assertRaises(ha_sync.HAError):
            set_ha_link.check_ha_link("http://ha", "t", "todo.x", make_link=lambda *a: link)

    def test_apply_then_clear(self):
        values = {"OTHER": "1"}
        set_ha_link.apply_ha_link(values, "http://ha", "t", "todo.x")
        self.assertEqual(values[set_ha_link.HA_URL_ENV], "http://ha")
        set_ha_link.clear_ha_link(values)
        self.assertEqual(values, {"OTHER": "1"})


class GCalTests(unittest.TestCase):
    def test_check_service_account(self):
        account = mock.Mock(client_email="bot@x.iam.gserviceaccount.com")
        link = mock.Mock(check=mock.Mock(return_value="Family"))
        email, name = set_gcal.check_service_account(
            Path("k.json"), "cal@group", load=lambda p: account, make_link=lambda c, a: link)
        self.assertEqual((email, name), ("bot@x.iam.gserviceaccount.com", "Family"))

    def test_service_account_replaces_oauth(self):
        values = {gcal.REFRESH_TOKEN_ENV: "r", gcal.CLIENT_ID_ENV: "c", gcal.CLIENT_SECRET_ENV: "s"}
        set_gcal.apply_service_account(values, "cal", Path("/k.json"))
        self.assertEqual(values, {gcal.CALENDAR_ID_ENV: "cal", gcal.CREDENTIALS_ENV: str(Path("/k.json"))})

    def test_oauth_replaces_service_account(self):
        values = {gcal.CREDENTIALS_ENV: "/k.json"}
        set_gcal_oauth.apply_oauth(values, "cal", "c", "s", "r")
        self.assertNotIn(gcal.CREDENTIALS_ENV, values)
        self.assertEqual(values[gcal.REFRESH_TOKEN_ENV], "r")

    def test_clear(self):
        values = {k: "x" for k in set_gcal.GCAL_KEYS} | {"OTHER": "1"}
        set_gcal.clear_gcal(values)
        self.assertEqual(values, {"OTHER": "1"})
