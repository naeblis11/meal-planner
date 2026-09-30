"""The one-way push of a week's meals onto a Google calendar.

Everything here runs against FakeGoogle rather than the real API. What is
being tested is the *policy*: that the push is idempotent, that it only
ever touches events it created, and that it never reads the family's own
entries back into the meal plan.
"""
import base64
import io
import os
import json
import sqlite3
import tempfile
import unittest
import unittest.mock
import urllib.error
import urllib.parse
from datetime import date, datetime, timedelta
from pathlib import Path

import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import db
import gcal
import meal_calendar


class FakeGoogle:
    """A calendar the tests can inspect. Ids are handed out in order."""

    def __init__(self):
        self.events = {}
        self.calls = []
        self._next = 0
        self.fail_next_update_with = None

    # -- the GCalLink surface push_week uses --------------------------------
    @property
    def calendar_id(self):
        return "family@group.calendar.google.com"

    def insert_event(self, event):
        self._next += 1
        event_id = f"evt{self._next}"
        self.events[event_id] = dict(event)
        self.calls.append(("insert", event_id))
        return event_id

    def update_event(self, event_id, event):
        if self.fail_next_update_with is not None:
            status = self.fail_next_update_with
            self.fail_next_update_with = None
            raise gcal.GCalHTTPError(status, f"gone ({status})")
        if event_id not in self.events:
            raise gcal.GCalHTTPError(404, "no such event")
        self.events[event_id] = dict(event)
        self.calls.append(("update", event_id))

    def delete_event(self, event_id):
        if event_id not in self.events:
            raise gcal.GCalHTTPError(410, "already gone")
        del self.events[event_id]
        self.calls.append(("delete", event_id))

    # -- helpers ------------------------------------------------------------
    def summaries(self):
        return sorted(e["summary"] for e in self.events.values())


class GCalPushTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.conn = sqlite3.connect(Path(self.tmp.name) / "test.db")
        self.addCleanup(self.conn.close)  # Windows will not delete an open file
        self.conn.row_factory = sqlite3.Row
        db.init_db(self.conn)
        self.google = FakeGoogle()
        self.week = date(2026, 9, 28)  # a Monday

    def add_recipe(self, name):
        cur = self.conn.execute(
            'INSERT INTO "recipe" ("file_path", "file_mtime", "name", "raw_yaml") '
            "VALUES (?, ?, ?, ?)",
            (f"{name}.yaml", 0.0, name, f"name: {name}"),
        )
        self.conn.commit()
        return cur.lastrowid

    def plan(self, day, slot, recipe_id, servings=None):
        self.conn.execute(
            'INSERT INTO "meal_plan" ("date", "slot", "recipe_id", "servings") VALUES (?, ?, ?, ?) '
            'ON CONFLICT("date", "slot") DO UPDATE SET "recipe_id" = excluded."recipe_id", '
            '"servings" = excluded."servings"',
            (day, slot, recipe_id, servings),
        )
        self.conn.commit()

    def push(self):
        return gcal.push_week(self.conn, self.google, self.week)

    # -- the basics ---------------------------------------------------------
    def test_pushes_a_planned_meal_as_a_timed_event(self):
        self.plan("2026-09-28", "Dinner", self.add_recipe("Chicken Parmesan"))
        result = self.push()

        self.assertEqual(result.added, 1)
        self.assertEqual(self.google.summaries(), ["Dinner: Chicken Parmesan"])
        event = next(iter(self.google.events.values()))
        self.assertNotIn("date", event["start"], "must be timed, not an all-day banner")
        self.assertTrue(event["start"]["dateTime"].startswith("2026-09-28T18:00:00"))
        self.assertTrue(event["end"]["dateTime"].startswith("2026-09-28T19:00:00"))

    def test_empty_week_writes_nothing(self):
        result = self.push()
        self.assertEqual((result.added, result.updated, result.removed), (0, 0, 0))
        self.assertEqual(self.google.events, {})

    def test_all_three_slots_are_pushed(self):
        rid = self.add_recipe("Oatmeal")
        for slot in ("Breakfast", "Lunch", "Dinner"):
            self.plan("2026-09-28", slot, rid)
        self.push()
        self.assertEqual(len(self.google.events), 3)

    def test_servings_and_a_link_back_go_in_the_description(self):
        rid = self.add_recipe("Chili")
        self.plan("2026-09-28", "Dinner", rid, servings="6")
        gcal.push_week(self.conn, self.google, self.week, app_base_url="https://meal-planner.example/")
        description = next(iter(self.google.events.values()))["description"]
        self.assertIn("Servings: 6", description)
        self.assertIn(f"https://meal-planner.example/recipes/{rid}", description)

    # -- idempotency: the whole point of the button --------------------------
    def test_pressing_twice_changes_nothing_the_second_time(self):
        self.plan("2026-09-28", "Dinner", self.add_recipe("Chicken Parmesan"))
        self.push()
        self.google.calls.clear()

        result = self.push()
        self.assertEqual((result.added, result.updated, result.removed), (0, 0, 0))
        self.assertEqual(result.unchanged, 1)
        self.assertEqual(self.google.calls, [], "a second press must not call Google at all")
        self.assertEqual(len(self.google.events), 1)

    def test_changing_the_recipe_updates_the_same_event(self):
        self.plan("2026-09-28", "Dinner", self.add_recipe("Chicken Parmesan"))
        self.push()
        event_id = next(iter(self.google.events))

        self.plan("2026-09-28", "Dinner", self.add_recipe("Beef Stew"))
        result = self.push()

        self.assertEqual((result.added, result.updated), (0, 1))
        self.assertEqual(list(self.google.events), [event_id], "must reuse the event, not add one")
        self.assertEqual(self.google.summaries(), ["Dinner: Beef Stew"])

    def test_changing_only_servings_updates_the_event(self):
        rid = self.add_recipe("Chili")
        self.plan("2026-09-28", "Dinner", rid, servings="4")
        self.push()
        self.plan("2026-09-28", "Dinner", rid, servings="8")
        result = self.push()
        self.assertEqual(result.updated, 1)
        self.assertIn("Servings: 8", next(iter(self.google.events.values()))["description"])

    def test_removing_a_meal_removes_its_event(self):
        self.plan("2026-09-28", "Dinner", self.add_recipe("Chicken Parmesan"))
        self.push()
        meal_calendar.unassign_meal(self.conn, "2026-09-28", "Dinner")

        result = self.push()
        self.assertEqual(result.removed, 1)
        self.assertEqual(self.google.events, {})

    def test_moving_a_meal_to_another_day_moves_the_event(self):
        rid = self.add_recipe("Tacos")
        self.plan("2026-09-28", "Dinner", rid)
        self.push()
        meal_calendar.unassign_meal(self.conn, "2026-09-28", "Dinner")
        self.plan("2026-09-30", "Dinner", rid)

        self.push()
        self.assertEqual(len(self.google.events), 1, "the old day's event must not linger")
        moved = next(iter(self.google.events.values()))["start"]["dateTime"]
        self.assertTrue(moved.startswith("2026-09-30T18:00:00"))

    # -- the family's own entries are untouchable ---------------------------
    def test_never_touches_events_it_did_not_create(self):
        theirs = self.google.insert_event({"summary": "Dentist", "start": {"date": "2026-09-29"}})
        self.plan("2026-09-28", "Dinner", self.add_recipe("Chicken Parmesan"))
        self.push()
        meal_calendar.unassign_meal(self.conn, "2026-09-28", "Dinner")
        self.push()

        self.assertIn(theirs, self.google.events, "their own event must survive everything")
        self.assertEqual(self.google.events[theirs]["summary"], "Dentist")

    def test_a_meal_event_deleted_by_hand_is_recreated_not_resurrected(self):
        self.plan("2026-09-28", "Dinner", self.add_recipe("Chicken Parmesan"))
        self.push()
        # They delete it on the calendar; we only find out when we next try
        # to update it, and Google answers 410.
        self.google.events.clear()
        self.google.fail_next_update_with = 410
        self.plan("2026-09-28", "Dinner", self.add_recipe("Beef Stew"))

        result = self.push()
        self.assertEqual(result.added, 1)
        self.assertEqual(self.google.summaries(), ["Dinner: Beef Stew"])

    def test_removal_survives_the_event_already_being_gone(self):
        self.plan("2026-09-28", "Dinner", self.add_recipe("Chicken Parmesan"))
        self.push()
        self.google.events.clear()
        meal_calendar.unassign_meal(self.conn, "2026-09-28", "Dinner")

        result = self.push()  # must not raise
        self.assertEqual(result.removed, 1)
        rows = self.conn.execute('SELECT COUNT(*) FROM "gcal_event"').fetchone()[0]
        self.assertEqual(rows, 0, "a vanished event must stop being tracked")

    def test_other_weeks_are_left_alone(self):
        rid = self.add_recipe("Chicken Parmesan")
        self.plan("2026-09-28", "Dinner", rid)   # this week
        self.plan("2026-10-07", "Dinner", rid)   # next week
        self.push()
        self.assertEqual(len(self.google.events), 1)

        gcal.push_week(self.conn, self.google, date(2026, 10, 5))
        self.assertEqual(len(self.google.events), 2, "pushing week 2 must not drop week 1")

    # -- reporting ----------------------------------------------------------
    def test_summary_reads_plainly(self):
        self.plan("2026-09-28", "Dinner", self.add_recipe("Chicken Parmesan"))
        self.assertEqual(self.push().summary(), "Google calendar updated: 1 added.")
        self.assertEqual(self.push().summary(), "Google calendar was already up to date.")


class BuildEventTests(unittest.TestCase):
    def test_each_slot_lands_at_its_own_time(self):
        meal = {"recipe_id": 1, "recipe_name": "X"}
        for slot, expected in (("Breakfast", "07:00:00"), ("Lunch", "12:00:00"),
                               ("Dinner", "18:00:00")):
            with self.subTest(slot=slot):
                event = gcal.build_event(slot, meal, "2026-09-28")
                self.assertTrue(event["start"]["dateTime"].startswith(f"2026-09-28T{expected}"))

    def test_each_meal_is_an_hour_long(self):
        event = gcal.build_event("Lunch", {"recipe_id": 1, "recipe_name": "X"}, "2026-09-28")
        start = datetime.fromisoformat(event["start"]["dateTime"])
        end = datetime.fromisoformat(event["end"]["dateTime"])
        self.assertEqual(end - start, timedelta(hours=1))

    def test_the_local_time_holds_across_the_year(self):
        """Whatever the machine's timezone does with daylight saving, dinner
        is at six in the evening on every date -- the offset moves, not the
        clock time."""
        meal = {"recipe_id": 1, "recipe_name": "X"}
        for day in ("2026-01-15", "2026-03-08", "2026-07-04", "2026-11-01", "2026-12-31"):
            with self.subTest(day=day):
                event = gcal.build_event("Dinner", meal, day)
                self.assertTrue(event["start"]["dateTime"].startswith(f"{day}T18:00:00"))

    def test_a_stamp_carries_a_utc_offset_so_google_is_not_guessing(self):
        event = gcal.build_event("Dinner", {"recipe_id": 1, "recipe_name": "X"}, "2026-09-28")
        parsed = datetime.fromisoformat(event["start"]["dateTime"])
        self.assertIsNotNone(parsed.tzinfo, "an offset-naive stamp would be read as UTC")

    def test_marked_free_so_it_does_not_look_like_a_commitment(self):
        event = gcal.build_event("Dinner", {"recipe_id": 1, "recipe_name": "X"}, "2026-09-28")
        self.assertEqual(event["transparency"], "transparent")

    def test_same_meal_hashes_the_same(self):
        meal = {"recipe_id": 1, "recipe_name": "X", "servings": "4"}
        a = gcal.content_hash(gcal.build_event("Dinner", meal, "2026-09-28"))
        b = gcal.content_hash(gcal.build_event("Dinner", dict(meal), "2026-09-28"))
        self.assertEqual(a, b)


class DescriptionTests(unittest.TestCase):
    """What the family actually reads when they tap the event."""

    MEAL = {"recipe_id": 7, "recipe_name": "Chili", "servings": "6"}

    @staticmethod
    def rows(*triples):
        return [{"name": n, "amount": a, "unit": u, "section": sec}
                for n, a, u, sec in triples]

    def test_lists_ingredients_with_their_amounts(self):
        event = gcal.build_event("Dinner", self.MEAL, "2026-09-28", ingredients=self.rows(
            ("ground beef", "1", "lb", None),
            ("kidney beans", "2", "cups", None),
        ))
        self.assertIn("Ingredients:", event["description"])
        self.assertIn("- 1 lb ground beef", event["description"])
        self.assertIn("- 2 cups kidney beans", event["description"])

    def test_keeps_sub_recipe_sections(self):
        event = gcal.build_event("Dinner", self.MEAL, "2026-09-28", ingredients=self.rows(
            ("flour", "2", "cups", None),
            ("butter", "1/2", "cup", "Topping"),
        ))
        self.assertIn("Topping:", event["description"])

    def test_an_ingredient_with_no_amount_still_reads_properly(self):
        event = gcal.build_event("Dinner", self.MEAL, "2026-09-28",
                                 ingredients=self.rows(("salt to taste", None, None, None)))
        self.assertIn("- salt to taste", event["description"])
        self.assertNotIn("-  salt", event["description"], "no double space from a blank amount")

    def test_no_ingredients_means_no_empty_heading(self):
        event = gcal.build_event("Dinner", self.MEAL, "2026-09-28", ingredients=[])
        self.assertNotIn("Ingredients:", event["description"])

    def test_servings_and_a_link_still_appear(self):
        event = gcal.build_event("Dinner", self.MEAL, "2026-09-28", "https://meal-planner.example",
                                 ingredients=self.rows(("flour", "2", "cups", None)))
        self.assertIn("Servings: 6", event["description"])
        self.assertIn("https://meal-planner.example/recipes/7", event["description"])


class ServiceAccountTests(unittest.TestCase):
    def test_rejects_a_file_that_is_not_a_service_account_key(self):
        with self.assertRaises(gcal.GCalError) as caught:
            gcal.ServiceAccount({"type": "authorized_user"})
        self.assertIn("client_email", str(caught.exception))

    def test_missing_key_file_is_a_clear_error(self):
        with self.assertRaises(gcal.GCalError) as caught:
            gcal.ServiceAccount.from_file(Path("no-such-key.json"))
        self.assertIn("no service account key", str(caught.exception))

    def test_not_configured_means_no_link_rather_than_an_error(self):
        self.assertIsNone(gcal.link_from_env({}))
        self.assertIsNone(gcal.link_from_env({gcal.CALENDAR_ID_ENV: "x"}))


class ServiceAccountTokenTests(unittest.TestCase):
    """The JWT bearer grant is written by hand here rather than pulled in
    with google-auth, so it gets tested by hand too: sign with a real key,
    then verify the assertion the way Google's token endpoint would."""

    @classmethod
    def setUpClass(cls):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import rsa

        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        cls.public_key = key.public_key()
        cls.pem = key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        ).decode("ascii")

    def make_account(self):
        return gcal.ServiceAccount({
            "client_email": "planner@example.iam.gserviceaccount.com",
            "private_key": self.pem,
            "token_uri": "https://oauth2.googleapis.com/token",
        })

    def fake_token_endpoint(self, expires_in=3600):
        """Stands in for Google: records the request, returns a token."""
        captured = {}

        class Response:
            def __init__(self, payload):
                self._payload = payload

            def read(self):
                return json.dumps(self._payload).encode("utf-8")

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        def urlopen(request, timeout=None):
            captured["url"] = request.full_url
            captured["body"] = dict(urllib.parse.parse_qsl(request.data.decode("ascii")))
            captured["calls"] = captured.get("calls", 0) + 1
            return Response({"access_token": "ya29.fake", "expires_in": expires_in})

        return captured, urlopen

    def decode(self, assertion):
        header_b64, claims_b64, signature_b64 = assertion.split(".")

        def unpad(part):
            return base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))

        return (
            json.loads(unpad(header_b64)),
            json.loads(unpad(claims_b64)),
            unpad(signature_b64),
            f"{header_b64}.{claims_b64}".encode("ascii"),
        )

    def test_mints_a_token_with_a_correctly_signed_assertion(self):
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding

        account = self.make_account()
        captured, urlopen = self.fake_token_endpoint()
        with unittest.mock.patch("urllib.request.urlopen", urlopen):
            self.assertEqual(account.access_token(), "ya29.fake")

        self.assertEqual(captured["body"]["grant_type"],
                         "urn:ietf:params:oauth:grant-type:jwt-bearer")
        header, claims, signature, signed = self.decode(captured["body"]["assertion"])

        self.assertEqual(header, {"alg": "RS256", "typ": "JWT"})
        self.assertEqual(claims["iss"], "planner@example.iam.gserviceaccount.com")
        self.assertEqual(claims["aud"], "https://oauth2.googleapis.com/token")
        self.assertEqual(claims["scope"], gcal.SCOPE)
        self.assertGreater(claims["exp"], claims["iat"])

        # The real check: Google must be able to verify this with the
        # public half of the key. A wrong signing input or encoding fails here.
        self.public_key.verify(signature, signed, padding.PKCS1v15(), hashes.SHA256())

    def test_the_token_is_reused_until_it_nears_expiry(self):
        account = self.make_account()
        captured, urlopen = self.fake_token_endpoint()
        with unittest.mock.patch("urllib.request.urlopen", urlopen):
            account.access_token()
            account.access_token()
            account.access_token()
        self.assertEqual(captured["calls"], 1, "must not re-mint a token per API call")

    def test_a_nearly_expired_token_is_re_minted(self):
        account = self.make_account()
        # Google's grace is 60s in gcal.py, so a 30s token is always stale.
        captured, urlopen = self.fake_token_endpoint(expires_in=30)
        with unittest.mock.patch("urllib.request.urlopen", urlopen):
            account.access_token()
            account.access_token()
        self.assertEqual(captured["calls"], 2)


class OAuthUserTests(unittest.TestCase):
    """Signing in as the user, rather than as a service account."""

    def fake_token_endpoint(self, payload=None, status=None, body=None):
        captured = {}

        class Response:
            def __init__(self, data):
                self._data = data

            def read(self):
                return json.dumps(self._data).encode("utf-8")

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        def urlopen(request, timeout=None):
            captured["url"] = request.full_url
            captured["body"] = dict(urllib.parse.parse_qsl(request.data.decode("ascii")))
            captured["calls"] = captured.get("calls", 0) + 1
            if status is not None:
                raise urllib.error.HTTPError(
                    request.full_url, status, "nope", {},
                    io.BytesIO(json.dumps(body or {}).encode("utf-8")),
                )
            return Response(payload or {"access_token": "ya29.user", "expires_in": 3600})

        return captured, urlopen

    def make_user(self):
        return gcal.OAuthUser("client-id", "client-secret", "1//refresh")

    def test_swaps_the_refresh_token_for_an_access_token(self):
        user = self.make_user()
        captured, urlopen = self.fake_token_endpoint()
        with unittest.mock.patch("urllib.request.urlopen", urlopen):
            self.assertEqual(user.access_token(), "ya29.user")
        self.assertEqual(captured["body"]["grant_type"], "refresh_token")
        self.assertEqual(captured["body"]["refresh_token"], "1//refresh")
        self.assertEqual(captured["body"]["client_id"], "client-id")

    def test_the_token_is_reused_until_it_nears_expiry(self):
        user = self.make_user()
        captured, urlopen = self.fake_token_endpoint()
        with unittest.mock.patch("urllib.request.urlopen", urlopen):
            user.access_token()
            user.access_token()
        self.assertEqual(captured["calls"], 1)

    def test_a_revoked_token_says_what_to_do_about_it(self):
        user = self.make_user()
        _, urlopen = self.fake_token_endpoint(
            status=400, body={"error": "invalid_grant",
                              "error_description": "Token has been expired or revoked."})
        with unittest.mock.patch("urllib.request.urlopen", urlopen):
            with self.assertRaises(gcal.GCalError) as caught:
                user.access_token()
        message = str(caught.exception)
        self.assertIn("invalid_grant", message)
        self.assertIn("set_gcal_oauth.py", message, "must tell the user how to fix it")

    def test_incomplete_details_are_refused_up_front(self):
        with self.assertRaises(gcal.GCalError):
            gcal.OAuthUser("client-id", "", "1//refresh")

    def test_it_is_interchangeable_with_a_service_account(self):
        """GCalLink must not care which of the two it was handed."""
        for name in ("access_token",):
            self.assertTrue(hasattr(gcal.OAuthUser, name))
            self.assertTrue(hasattr(gcal.ServiceAccount, name))


class CredentialsFromEnvTests(unittest.TestCase):
    def test_nothing_configured_is_not_an_error(self):
        self.assertIsNone(gcal.credentials_from_env({}))
        self.assertIsNone(gcal.link_from_env({gcal.CALENDAR_ID_ENV: "cal"}))

    def test_a_refresh_token_gives_an_oauth_sign_in(self):
        credentials = gcal.credentials_from_env({
            gcal.CLIENT_ID_ENV: "id",
            gcal.CLIENT_SECRET_ENV: "secret",
            gcal.REFRESH_TOKEN_ENV: "1//refresh",
        })
        self.assertIsInstance(credentials, gcal.OAuthUser)

    def test_your_own_account_wins_over_a_leftover_service_account_key(self):
        credentials = gcal.credentials_from_env({
            gcal.CLIENT_ID_ENV: "id",
            gcal.CLIENT_SECRET_ENV: "secret",
            gcal.REFRESH_TOKEN_ENV: "1//refresh",
            gcal.CREDENTIALS_ENV: "should-not-be-opened.json",
        })
        self.assertIsInstance(credentials, gcal.OAuthUser)

    def test_a_link_needs_both_a_calendar_and_a_sign_in(self):
        self.assertIsNone(gcal.link_from_env({
            gcal.CLIENT_ID_ENV: "id", gcal.CLIENT_SECRET_ENV: "s",
            gcal.REFRESH_TOKEN_ENV: "1//r",
        }))
        link = gcal.link_from_env({
            gcal.CALENDAR_ID_ENV: "cal",
            gcal.CLIENT_ID_ENV: "id", gcal.CLIENT_SECRET_ENV: "s",
            gcal.REFRESH_TOKEN_ENV: "1//r",
        })
        self.assertEqual(link.calendar_id, "cal")


class CheckTests(unittest.TestCase):
    """check() runs before anything is saved, so it must work under the only
    scope we ask for."""

    def test_uses_an_events_listing_not_calendar_metadata(self):
        seen = {}

        class Credentials:
            def access_token(self):
                return "tok"

        link = gcal.GCalLink("family@group.calendar.google.com", Credentials())

        def fake_request(method, path, payload=None):
            seen["method"], seen["path"] = method, path
            return {"summary": "Family"}

        link._request = fake_request
        self.assertEqual(link.check(), "Family")
        self.assertEqual(seen["method"], "GET")
        self.assertIn("/events", seen["path"])
        # calendars.get is NOT authorised by the calendar.events scope and
        # answers 403 even when everything is configured correctly.
        self.assertTrue(seen["path"].split("?")[0].endswith("/events"),
                        f"must not call calendars.get: {seen['path']}")


class StoredKeyPathTests(unittest.TestCase):
    """Where the service account key is filed.

    It must land in the same folder the app reads recipes from. When those
    two disagree -- which is what MEAL_PLANNER_DATA_DIR exists to control --
    the key is written somewhere the app never looks and the link silently
    never works.
    """

    def test_follows_the_configured_data_folder(self):
        with unittest.mock.patch.dict(os.environ,
                                      {"MEAL_PLANNER_DATA_DIR": os.path.join("X:", "data")}):
            self.assertEqual(gcal.stored_key_path().parent.name, "data")

    def test_sits_beside_the_recipes(self):
        import paths
        with unittest.mock.patch.dict(os.environ,
                                      {"MEAL_PLANNER_DATA_DIR": os.path.join("X:", "data")}):
            self.assertEqual(gcal.stored_key_path().parent, paths.recipes_dir().parent)


if __name__ == "__main__":
    unittest.main()
