import tempfile
import unittest
from pathlib import Path

import gcal
import ha_sync
import set_api_token
import set_ha_link
import wizard_steps as ws
from tests.test_ha_sync import FakeHA
from tests.test_wizard_io import ScriptedConsole
from werkzeug.security import check_password_hash


def ctx(target=ws.TARGET_WINDOWS, ha=None, **kw):
    fake = ha or FakeHA()
    return ws.Context(target=target, make_ha_link=lambda *a: fake, **kw)


class PasswordStep(unittest.TestCase):
    def test_first_run_asks_twice_and_hashes(self):
        values = {}
        ws.ask_password(ScriptedConsole(["", "a", "b", "pw", "pw"]), values, ctx())
        self.assertTrue(check_password_hash(values["MEAL_PLANNER_PASSWORD_HASH"], "pw"))

    def test_rerun_can_keep_it(self):
        values = {"MEAL_PLANNER_PASSWORD_HASH": "h"}
        ws.ask_password(ScriptedConsole([""]), values, ctx())
        self.assertEqual(values["MEAL_PLANNER_PASSWORD_HASH"], "h")


class AccessStep(unittest.TestCase):
    def test_windows_default_is_this_computer_only(self):
        values = {}
        ws.ask_access(ScriptedConsole([""]), values, ctx())
        self.assertNotIn("MEAL_PLANNER_HOST", values)

    def test_home_network(self):
        values = {}
        ws.ask_access(ScriptedConsole(["2"]), values, ctx())
        self.assertEqual(values["MEAL_PLANNER_HOST"], "0.0.0.0")

    def test_existing_home_network_is_the_default(self):
        values = {"MEAL_PLANNER_HOST": "0.0.0.0"}
        ws.ask_access(ScriptedConsole([""]), values, ctx())
        self.assertEqual(values["MEAL_PLANNER_HOST"], "0.0.0.0")

    def test_pi_does_not_ask(self):
        values = {}
        ws.ask_access(ScriptedConsole([]), values, ctx(ws.TARGET_PI))
        self.assertEqual(values["MEAL_PLANNER_HOST"], "0.0.0.0")


class RemoteStep(unittest.TestCase):
    def test_on_for_pi_takes_a_key(self):
        values, c = {}, ctx(ws.TARGET_PI)
        con = ScriptedConsole(["y", "tskey-auth-abc"])
        ws.ask_remote(con, values, c)
        self.assertEqual(values["MEAL_PLANNER_BEHIND_PROXY"], "1")
        self.assertEqual(c.tailscale_key, "tskey-auth-abc")
        self.assertIn("login.tailscale.com/admin/settings/keys", con.text)

    def test_pi_without_a_key_gets_the_working_sequence(self):
        con = ScriptedConsole(["y", ""])
        ws.ask_remote(con, {}, ctx(ws.TARGET_PI))
        for line in ("curl -fsSL https://tailscale.com/install.sh | sh",
                     "sudo tailscale up --ssh",
                     "sudo ~/Meal_Planner/pi/setup-tailscale.sh"):
            self.assertIn(line, con.text)

    def test_on_for_windows_prints_serve_command(self):
        con = ScriptedConsole(["y"])
        ws.ask_remote(con, {}, ctx())
        self.assertIn("tailscale serve --bg --https=443 http://127.0.0.1:5000", con.text)

    def test_off_removes_the_proxy_setting(self):
        values = {"MEAL_PLANNER_BEHIND_PROXY": "1"}
        c = ctx()
        ws.ask_remote(ScriptedConsole(["n"]), values, c)
        self.assertNotIn("MEAL_PLANNER_BEHIND_PROXY", values)
        self.assertFalse(c.features["remote"])


class HomeAssistantStep(unittest.TestCase):
    def test_bad_token_then_good(self):
        fake = FakeHA()
        calls = {"n": 0}
        def make(url, token, entity):
            calls["n"] += 1
            fake.fail = token == "bad"
            return fake
        values, c = {}, ws.Context(target=ws.TARGET_WINDOWS, make_ha_link=make)
        con = ScriptedConsole(["y", "", "bad", "", "1", "", "good", ""])
        ws.ask_home_assistant(con, values, c)
        self.assertEqual(values[set_ha_link.HA_TOKEN_ENV], "good")
        self.assertIn("Long-lived access tokens", con.text)
        self.assertTrue(values[set_api_token.API_TOKEN_ENV])
        self.assertEqual(values["MEAL_PLANNER_HOST"], "0.0.0.0")
        self.assertTrue(c.features["ha"])

    def test_unreachable_can_be_saved_anyway(self):
        fake = FakeHA(); fake.fail = True
        values, c = {}, ctx(ws.TARGET_PI, ha=fake)
        ws.ask_home_assistant(ScriptedConsole(["y", "", "tok", "", "2"]), values, c)
        self.assertEqual(values[set_ha_link.HA_TOKEN_ENV], "tok")
        self.assertTrue(c.features["ha"])

    def test_unreachable_can_be_skipped(self):
        fake = FakeHA(); fake.fail = True
        values, c = {}, ctx(ws.TARGET_PI, ha=fake)
        ws.ask_home_assistant(ScriptedConsole(["y", "", "tok", "", "3"]), values, c)
        self.assertNotIn(set_ha_link.HA_TOKEN_ENV, values)
        self.assertFalse(c.features["ha"])

    def test_off_clears_ha_but_alexa_keeps_the_api_token(self):
        values = {set_ha_link.HA_URL_ENV: "u", set_ha_link.HA_TOKEN_ENV: "t",
                  set_ha_link.HA_TODO_ENTITY_ENV: "e", set_api_token.API_TOKEN_ENV: "api"}
        c = ctx()
        ws.ask_home_assistant(ScriptedConsole(["n"]), values, c)
        ws.ask_alexa(ScriptedConsole(["y"]), values, c)
        self.assertNotIn(set_ha_link.HA_URL_ENV, values)
        self.assertEqual(values[set_api_token.API_TOKEN_ENV], "api")

    def test_alexa_defaults_to_yes_when_a_token_exists(self):
        for ha in (False, True):
            values = {set_api_token.API_TOKEN_ENV: "api"}
            c = ctx()
            c.features["ha"] = ha
            ws.ask_alexa(ScriptedConsole([""]), values, c)
            self.assertTrue(c.features["alexa"])
            self.assertEqual(values[set_api_token.API_TOKEN_ENV], "api")

    def test_alexa_defaults_to_no_without_a_token(self):
        c = ctx()
        ws.ask_alexa(ScriptedConsole([""]), {}, c)
        self.assertFalse(c.features["alexa"])

    def test_everything_off_drops_the_api_token(self):
        values = {set_api_token.API_TOKEN_ENV: "api"}
        c = ctx()
        ws.ask_home_assistant(ScriptedConsole(["n"]), values, c)
        ws.ask_alexa(ScriptedConsole(["n"]), values, c)
        self.assertNotIn(set_api_token.API_TOKEN_ENV, values)


class GoogleCalendarStep(unittest.TestCase):
    def test_service_account(self):
        tmp = Path(tempfile.mkdtemp()); key = tmp / "k.json"; key.write_text("{}")
        account = type("A", (), {"client_email": "bot@p.iam.gserviceaccount.com"})()
        link = type("L", (), {"check": lambda self: "Family"})()
        c = ws.Context(target=ws.TARGET_WINDOWS, load_service_account=lambda p: account,
                       make_gcal_link=lambda cal, acct: link)
        values = {gcal.REFRESH_TOKEN_ENV: "old"}
        ws.ask_google_calendar(ScriptedConsole(["y", "1", str(key), "cal@group"]), values, c)
        self.assertEqual(values[gcal.CALENDAR_ID_ENV], "cal@group")
        self.assertNotIn(gcal.REFRESH_TOKEN_ENV, values)
        self.assertEqual(c.gcal_key_source, key)

    def test_own_account(self):
        c = ws.Context(target=ws.TARGET_WINDOWS,
                       oauth_sign_in=lambda cid, sec: "refresh",
                       oauth_list_calendars=lambda cred: [{"id": "primary@x", "summary": "Me"}])
        values = {gcal.CREDENTIALS_ENV: "/k"}
        con = ScriptedConsole(["y", "2", "cid", "sec", "1"])
        ws.ask_google_calendar(con, values, c)
        self.assertEqual(values[gcal.CALENDAR_ID_ENV], "primary@x")
        self.assertEqual(values[gcal.REFRESH_TOKEN_ENV], "refresh")
        self.assertNotIn(gcal.CREDENTIALS_ENV, values)
        self.assertIn("7 days", con.text)
        self.assertTrue(c.features["gcal"])

    def test_service_account_failure_can_skip(self):
        tmp = Path(tempfile.mkdtemp()); key = tmp / "k.json"; key.write_text("{}")
        account = type("A", (), {"client_email": "bot@p.iam.gserviceaccount.com"})()
        def boom(self):
            raise gcal.GCalError("not shared")
        link = type("L", (), {"check": boom})()
        c = ws.Context(target=ws.TARGET_WINDOWS, load_service_account=lambda p: account,
                       make_gcal_link=lambda cal, acct: link)
        con = ScriptedConsole(["y", "1", str(key), "cal", "2"])
        ws.ask_google_calendar(con, {}, c)
        self.assertIn("bot@p.iam.gserviceaccount.com", con.text)
        self.assertFalse(c.features["gcal"])

    def test_off_clears(self):
        values = {gcal.CALENDAR_ID_ENV: "c", gcal.CREDENTIALS_ENV: "/k"}
        ws.ask_google_calendar(ScriptedConsole(["n", "n"]), values, ctx())
        self.assertEqual(values, {})

    def failing_account_ctx(self):
        account = type("A", (), {"client_email": "bot@p.iam.gserviceaccount.com"})()
        def boom(self):
            raise gcal.GCalError("not shared")
        link = type("L", (), {"check": boom})()
        return ws.Context(target=ws.TARGET_WINDOWS, load_service_account=lambda p: account,
                          make_gcal_link=lambda cal, acct: link)

    def test_rerun_can_keep_the_link(self):
        values = {gcal.CALENDAR_ID_ENV: "cal", gcal.CREDENTIALS_ENV: "/k"}
        c = ctx()
        ws.ask_google_calendar(ScriptedConsole([""]), values, c)
        self.assertEqual(values, {gcal.CALENDAR_ID_ENV: "cal", gcal.CREDENTIALS_ENV: "/k"})
        self.assertTrue(c.features["gcal"])

    def test_skip_after_a_failed_attempt_keeps_the_old_link(self):
        tmp = Path(tempfile.mkdtemp()); key = tmp / "k.json"; key.write_text("{}")
        old = {gcal.CALENDAR_ID_ENV: "old-cal", gcal.CREDENTIALS_ENV: "/old-key"}
        values = dict(old)
        c = self.failing_account_ctx()
        # not keeping it, yes to calendar, service account, key, new id, fails, skip
        con = ScriptedConsole(["n", "y", "1", str(key), "new-cal", "2"])
        ws.ask_google_calendar(con, values, c)
        self.assertEqual(values, old)
        self.assertTrue(c.features["gcal"])
        self.assertIsNone(c.gcal_key_source)

    def test_explicit_no_clears_an_existing_link(self):
        values = {gcal.CALENDAR_ID_ENV: "cal", gcal.CREDENTIALS_ENV: "/k"}
        c = ctx()
        ws.ask_google_calendar(ScriptedConsole(["n", "n"]), values, c)
        self.assertEqual(values, {})
        self.assertFalse(c.features["gcal"])

    def test_a_blank_key_path_skips(self):
        c = ctx()
        values = {}
        ws.ask_google_calendar(ScriptedConsole(["y", "1", ""]), values, c)
        self.assertEqual(values, {})
        self.assertFalse(c.features["gcal"])
