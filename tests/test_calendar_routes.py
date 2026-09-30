import shutil
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path

import app as app_module
import db
import gcal
import meal_calendar
import recipe_sync

FIXTURES = Path(__file__).parent / "fixtures"

from tests.test_gcal import FakeGoogle as FakeGoogleBase


class CalendarRouteTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp()
        self.recipes_dir = Path(self.tmp_dir) / "recipes"
        self.recipes_dir.mkdir()
        shutil.copy(FIXTURES / "banana-bread.yaml", self.recipes_dir)
        self.db_path = Path(self.tmp_dir) / "mealplanner.db"
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)

        conn = db.get_connection(self.db_path)
        try:
            self.recipe_id = conn.execute(
                "SELECT id FROM recipe WHERE name = 'Banana Bread'"
            ).fetchone()[0]
        finally:
            conn.close()

        self.app = app_module.create_app(self.recipes_dir, self.db_path)
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def tearDown(self):
        shutil.rmtree(self.tmp_dir, ignore_errors=True)


class TestCalendarView(CalendarRouteTestCase):
    def test_renders_current_week_by_default(self):
        response = self.client.get("/calendar")
        self.assertEqual(response.status_code, 200)

    def test_renders_specific_week(self):
        response = self.client.get("/calendar?week=2026-09-03")
        self.assertEqual(response.status_code, 200)

    def test_assigned_slot_links_to_recipe_detail(self):
        conn = db.get_connection(self.db_path)
        try:
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", self.recipe_id)
        finally:
            conn.close()

        # The calendar is a week-overview-plus-one-focused-day layout: only
        # the focused day's slots render in full detail, so the assigned
        # day must be explicitly focused via ?day= to see its recipe link.
        response = self.client.get("/calendar?week=2026-08-31&day=2026-09-01")
        body = response.get_data(as_text=True)
        self.assertIn(f"/recipes/{self.recipe_id}", body)
        self.assertIn("Banana Bread", body)

    def test_recipe_deleted_and_resynced_disappears_from_calendar_without_crash(self):
        conn = db.get_connection(self.db_path)
        try:
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", self.recipe_id)
        finally:
            conn.close()

        (self.recipes_dir / "banana-bread.yaml").unlink()
        recipe_sync.sync_recipes(self.recipes_dir, self.db_path)

        response = self.client.get("/calendar?week=2026-08-31&day=2026-09-01")
        self.assertEqual(response.status_code, 200)
        body = response.get_data(as_text=True)
        self.assertNotIn("Banana Bread", body)

    def test_has_generate_shopping_list_form_for_the_viewed_week(self):
        response = self.client.get("/calendar?week=2026-08-31")
        body = response.get_data(as_text=True)
        self.assertIn("/shopping-list/generate", body)
        self.assertIn('value="2026-08-31"', body)


class TestAssignMealForm(CalendarRouteTestCase):
    def test_lists_matching_recipes(self):
        response = self.client.get("/calendar/assign?date=2026-09-01&slot=Dinner&q=banana")
        self.assertEqual(response.status_code, 200)
        self.assertIn("Banana Bread", response.get_data(as_text=True))

    def test_bad_slot_is_400(self):
        response = self.client.get("/calendar/assign?date=2026-09-01&slot=Brunch")
        self.assertEqual(response.status_code, 400)

    def test_bad_date_is_400(self):
        response = self.client.get("/calendar/assign?date=not-a-date&slot=Dinner")
        self.assertEqual(response.status_code, 400)


class TestAssignMealSubmit(CalendarRouteTestCase):
    def test_creates_assignment_and_redirects(self):
        response = self.client.post(
            "/calendar/assign",
            data={"date": "2026-09-01", "slot": "Dinner", "recipe_id": str(self.recipe_id)},
        )
        self.assertEqual(response.status_code, 302)
        self.assertIn("/calendar", response.headers["Location"])

        conn = db.get_connection(self.db_path)
        try:
            row = conn.execute(
                'SELECT "recipe_id" FROM "meal_plan" WHERE "date" = ? AND "slot" = ?',
                ("2026-09-01", "Dinner"),
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(row["recipe_id"], self.recipe_id)

    def test_bad_slot_flashes_and_redirects_back_instead_of_500(self):
        response = self.client.post(
            "/calendar/assign",
            data={"date": "2026-09-01", "slot": "Brunch", "recipe_id": str(self.recipe_id)},
        )
        self.assertEqual(response.status_code, 302)
        self.assertIn("/calendar/assign", response.headers["Location"])

    def test_non_numeric_recipe_id_flashes_and_redirects_instead_of_500(self):
        response = self.client.post(
            "/calendar/assign",
            data={"date": "2026-09-01", "slot": "Dinner", "recipe_id": "not-a-number"},
        )
        self.assertEqual(response.status_code, 302)


class TestUnassignMealSubmit(CalendarRouteTestCase):
    def test_clears_assignment_and_redirects(self):
        conn = db.get_connection(self.db_path)
        try:
            meal_calendar.assign_meal(conn, "2026-09-01", "Dinner", self.recipe_id)
        finally:
            conn.close()

        response = self.client.post(
            "/calendar/unassign", data={"date": "2026-09-01", "slot": "Dinner"}
        )
        self.assertEqual(response.status_code, 302)

        conn = db.get_connection(self.db_path)
        try:
            row = conn.execute(
                'SELECT * FROM "meal_plan" WHERE "date" = ? AND "slot" = ?',
                ("2026-09-01", "Dinner"),
            ).fetchone()
        finally:
            conn.close()
        self.assertIsNone(row)


class TestGoogleCalendarPush(CalendarRouteTestCase):
    """The "Send this week to Google Calendar" button.

    The push itself is covered in test_gcal.py against a fake calendar;
    what matters here is that the route is wired up, gated on a link being
    configured, and never lets a Google outage take a page down.
    """

    def setUp(self):
        super().setUp()
        from tests.test_gcal import FakeGoogle
        self.google = FakeGoogle()
        self.monday = meal_calendar.get_week_start(date.today()).isoformat()

    def link(self):
        self.app.config["GCAL_LINK"] = self.google

    def test_button_is_hidden_until_a_calendar_is_linked(self):
        page = self.client.get("/calendar").get_data(as_text=True)
        self.assertNotIn("Send this week to Google Calendar", page)

    def test_button_appears_once_linked(self):
        self.link()
        page = self.client.get("/calendar").get_data(as_text=True)
        self.assertIn("Send this week to Google Calendar", page)

    def test_pushing_without_a_link_explains_rather_than_failing(self):
        response = self.client.post("/calendar/google-push",
                                    data={"week": self.monday}, follow_redirects=True)
        self.assertEqual(response.status_code, 200)
        self.assertIn("No Google calendar is linked", response.get_data(as_text=True))

    def test_pushes_the_week_and_reports_what_changed(self):
        self.link()
        conn = db.get_connection(self.db_path)
        try:
            meal_calendar.assign_meal(conn, self.monday, "Dinner", self.recipe_id, None)
        finally:
            conn.close()

        response = self.client.post("/calendar/google-push",
                                    data={"week": self.monday}, follow_redirects=True)
        self.assertIn("1 added", response.get_data(as_text=True))
        self.assertEqual(self.google.summaries(), ["Dinner: Banana Bread"])

    def test_a_google_outage_is_reported_not_a_500(self):
        class Broken(FakeGoogleBase):
            def insert_event(self, event):
                raise gcal.GCalError("could not reach Google: timed out")

        conn = db.get_connection(self.db_path)
        try:
            meal_calendar.assign_meal(conn, self.monday, "Dinner", self.recipe_id, None)
        finally:
            conn.close()
        self.app.config["GCAL_LINK"] = Broken()

        response = self.client.post("/calendar/google-push",
                                    data={"week": self.monday}, follow_redirects=True)
        self.assertEqual(response.status_code, 200)
        self.assertIn("Could not update the Google calendar", response.get_data(as_text=True))


if __name__ == "__main__":
    unittest.main()
