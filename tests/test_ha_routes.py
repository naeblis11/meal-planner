import shutil
import tempfile
import unittest
from pathlib import Path

import yaml

import app as app_module
import db
import ha_sync
import recipe_sync
import shopping_list
from tests.test_ha_sync import FakeHA

TOKEN = "ha-token-for-tests"
HA_DIR = Path(__file__).parent.parent / "ha"


class RecordingWorker:
    """What the routes see: something with request() and status()."""

    def __init__(self):
        self.requests = 0

    def request(self):
        self.requests += 1

    def run_once(self):
        self.requests += 100

    def status(self):
        return {"last_synced": None, "last_error": None, "last_error_at": None}


class HARouteCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        (self.tmp / "recipes").mkdir()
        self.db_path = self.tmp / "mealplanner.db"
        recipe_sync.sync_recipes(self.tmp / "recipes", self.db_path)
        self.ha = FakeHA()
        self.app = app_module.create_app(
            self.tmp / "recipes", self.db_path, api_token=TOKEN,
            ha_link=self.ha, start_ha_worker=False,
        )
        self.app.config["TESTING"] = True
        self.worker = RecordingWorker()
        self.app.extensions["ha_worker"] = self.worker
        self.client = self.app.test_client()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def conn(self):
        return db.get_connection(self.db_path)

    def sync(self, items, token=TOKEN):
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        return self.client.post("/api/ha/shopping-list/sync", json={"items": items}, headers=headers)


class TestWebhook(HARouteCase):
    def test_requires_the_bearer_token_not_a_session(self):
        self.assertEqual(self.sync([], token=None).status_code, 401)
        self.assertEqual(self.sync([], token="wrong").status_code, 401)
        response = self.sync([])
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json(), {"ok": True, "applied": 0})

    def test_rejects_a_body_without_an_items_list(self):
        response = self.client.post(
            "/api/ha/shopping-list/sync", json={"nope": 1},
            headers={"Authorization": f"Bearer {TOKEN}"},
        )
        self.assertEqual(response.status_code, 400)

    def test_applies_ha_changes_without_asking_for_a_push(self):
        conn = self.conn()
        try:
            _, item_id = shopping_list.add_item(conn, "apples", "Produce")
            ha_sync.push(conn, self.ha)
        finally:
            conn.close()
        self.ha.tick("apples")
        self.ha.user_add("milk")

        response = self.sync(self.ha.get_items())

        self.assertEqual(response.get_json()["applied"], 2)
        conn = self.conn()
        try:
            rows = {r["name"]: dict(r) for r in shopping_list.sync_rows(conn)}
        finally:
            conn.close()
        self.assertEqual(rows["apples"]["checked"], 1)
        self.assertIn("milk", rows)
        # Changes that came from HA must not echo straight back.
        self.assertEqual(self.worker.requests, 0)


class TestRoutesNotifyTheWorker(HARouteCase):
    def test_list_mutations_ask_for_a_push(self):
        self.client.post("/shopping-list/add", data={"name": "apples", "aisle": "Produce"})
        self.assertEqual(self.worker.requests, 1)
        conn = self.conn()
        try:
            item_id = shopping_list.sync_rows(conn)[0]["id"]
        finally:
            conn.close()
        self.client.post("/shopping-list/toggle", data={"item_id": item_id})
        self.client.post("/shopping-list/aisle", data={"item_id": item_id, "aisle": "Snacks"})
        self.client.post("/shopping-list/remove", data={"item_id": item_id})
        self.client.post("/shopping-list/clear")
        self.assertEqual(self.worker.requests, 5)

    def test_a_duplicate_add_changes_nothing_so_asks_for_nothing(self):
        self.client.post("/shopping-list/add", data={"name": "apples"})
        self.client.post("/shopping-list/add", data={"name": "apples"})
        self.assertEqual(self.worker.requests, 1)

    def test_voice_add_asks_for_a_push(self):
        self.client.post(
            "/api/voice/shopping-list", json={"item": "milk"},
            headers={"Authorization": f"Bearer {TOKEN}"},
        )
        self.assertEqual(self.worker.requests, 1)


class TestShoppingPage(HARouteCase):
    def test_shows_the_sync_button_and_status_when_linked(self):
        body = self.client.get("/shopping-list").get_data(as_text=True)
        self.assertIn("Sync with Home Assistant", body)
        self.assertIn("todo.shopping_list", body)

    def test_sync_button_runs_a_pass_now(self):
        response = self.client.post("/shopping-list/ha-sync", follow_redirects=True)
        self.assertIn("Synced with Home Assistant", response.get_data(as_text=True))
        self.assertEqual(self.worker.requests, 100)


class TestUnlinkedApp(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        (self.tmp / "recipes").mkdir()
        recipe_sync.sync_recipes(self.tmp / "recipes", self.tmp / "db")
        self.app = app_module.create_app(self.tmp / "recipes", self.tmp / "db", api_token=TOKEN)
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_no_link_means_no_worker_no_button_and_no_sync_route(self):
        self.assertIsNone(self.app.config["HA_LINK"])
        self.assertIsNone(self.app.extensions["ha_worker"])
        body = self.client.get("/shopping-list").get_data(as_text=True)
        self.assertNotIn("Sync with Home Assistant", body)
        self.assertEqual(self.client.post("/shopping-list/ha-sync").status_code, 404)
        # The webhook still answers (HA may be configured before the app is).
        response = self.client.post(
            "/api/ha/shopping-list/sync", json={"items": []},
            headers={"Authorization": f"Bearer {TOKEN}"},
        )
        self.assertEqual(response.status_code, 200)

    def test_env_vars_build_the_link(self):
        import os
        from unittest import mock
        with mock.patch.dict(os.environ, {
            app_module.HA_URL_ENV: "http://ha.local:8123",
            app_module.HA_TOKEN_ENV: "t",
            app_module.HA_TODO_ENTITY_ENV: "todo.custom",
        }):
            app = app_module.create_app(self.tmp / "recipes", self.tmp / "db", start_ha_worker=False)
        link = app.config["HA_LINK"]
        self.assertEqual((link.base_url, link.token, link.entity_id),
                         ("http://ha.local:8123", "t", "todo.custom"))


class TestHomeAssistantAssets(unittest.TestCase):
    def _config(self):
        text = (HA_DIR / "shopping-list.yaml").read_text(encoding="utf-8")
        return yaml.safe_load(text.replace("!secret meal_planner_auth", "SECRET"))

    def test_rest_command_posts_the_items_to_the_webhook_with_the_shared_token(self):
        command = self._config()["rest_command"]["meal_planner_shopping_sync"]
        self.assertEqual(command["url"], "http://meal-planner.local:5000/api/ha/shopping-list/sync")
        self.assertEqual(command["method"], "POST")
        self.assertEqual(command["headers"]["Authorization"], "SECRET")
        self.assertIn("items | tojson", command["payload"])

    def test_automation_watches_the_entity_and_forwards_the_whole_list(self):
        automation = self._config()["automation"][0]
        self.assertEqual(automation["triggers"][0]["entity_id"], ha_sync.DEFAULT_ENTITY)
        self.assertEqual(automation["mode"], "queued")
        actions = automation["actions"]
        self.assertEqual(actions[0]["action"], "todo.get_items")
        self.assertEqual(actions[0]["target"]["entity_id"], ha_sync.DEFAULT_ENTITY)
        self.assertEqual(actions[1]["action"], "rest_command.meal_planner_shopping_sync")
        self.assertIn("items", actions[1]["data"]["items"])

    def test_setup_guide_covers_every_step(self):
        guide = (HA_DIR / "SETUP-SHOPPING.md").read_text(encoding="utf-8")
        for needle in ("set_api_token.py", "Local To-do", "set_ha_link.py",
                       "shopping-list.yaml", "To-do list", "Sync with Home Assistant"):
            self.assertIn(needle, guide)


if __name__ == "__main__":
    unittest.main()
