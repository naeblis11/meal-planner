import json
import shutil
import tempfile
import threading
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

import db
import ha_sync
import shopping_list


class FakeHA:
    """Stands in for ha_sync.HALink with a Local To-do list's behaviour:
    ordered items, server-assigned uids, add/update/remove."""

    entity_id = ha_sync.DEFAULT_ENTITY

    def __init__(self):
        self.items = []
        self.counter = 0
        self.calls = []
        self.fail = False

    def _check(self):
        if self.fail:
            raise ha_sync.HAError("Home Assistant unreachable: down")

    def get_items(self):
        self._check()
        self.calls.append("get_items")
        return [dict(i) for i in self.items]

    def add_item(self, summary, description):
        self._check()
        self.calls.append(("add", summary, description))
        self.counter += 1
        self.items.append({"uid": f"u{self.counter}", "summary": summary,
                           "status": "needs_action", "description": description})

    def update_item(self, uid, *, rename=None, status=None, description=None):
        self._check()
        self.calls.append(("update", uid, rename, status, description))
        item = next(i for i in self.items if i["uid"] == uid)
        if rename is not None:
            item["summary"] = rename
        if status is not None:
            item["status"] = status
        if description is not None:
            item["description"] = description

    def remove_items(self, uids):
        self._check()
        self.calls.append(("remove", list(uids)))
        self.items = [i for i in self.items if i["uid"] not in set(uids)]

    # test helpers acting as the phone would
    def tick(self, summary):
        next(i for i in self.items if i["summary"] == summary)["status"] = "completed"

    def user_add(self, summary):
        self.add_item(summary, "")
        return self.items[-1]["uid"]

    def user_delete(self, summary):
        self.items = [i for i in self.items if i["summary"] != summary]


class SyncCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.db_path = self.tmp / "mealplanner.db"
        self.conn = db.get_connection(self.db_path)
        self.ha = FakeHA()

    def tearDown(self):
        self.conn.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def rows(self):
        return {r["name"]: dict(r) for r in shopping_list.sync_rows(self.conn)}


class TestSummaryText(unittest.TestCase):
    def test_build_drops_blanks(self):
        self.assertEqual(ha_sync.build_summary("ground beef", "2", "lb"), "2 lb ground beef")
        self.assertEqual(ha_sync.build_summary("eggs", None, None), "eggs")
        self.assertEqual(ha_sync.build_summary("eggs", "12", None), "12 eggs")

    def test_parse_round_trips_the_app_form(self):
        self.assertEqual(ha_sync.parse_summary("2 lb ground beef"), ("ground beef", "2", "lb"))
        self.assertEqual(ha_sync.parse_summary("1 1/2 cup milk"), ("milk", "1 1/2", "cup"))
        self.assertEqual(ha_sync.parse_summary("eggs"), ("eggs", None, None))
        self.assertEqual(ha_sync.parse_summary("12 eggs"), ("eggs", "12", None))

    def test_parse_reads_what_a_person_types(self):
        self.assertEqual(ha_sync.parse_summary("two pounds of ground beef"), ("ground beef", "2", "lb"))
        self.assertEqual(ha_sync.parse_summary("a dozen eggs"), ("eggs", "12", None))
        self.assertEqual(ha_sync.parse_summary("half a cup of sugar"), ("a cup of sugar", "1/2", None))
        self.assertEqual(ha_sync.parse_summary("bring the good olive oil"),
                         ("bring the good olive oil", None, None))
        self.assertEqual(ha_sync.parse_summary("2"), ("2", None, None))
        self.assertEqual(ha_sync.parse_summary("   "), ("", None, None))


class TestPush(SyncCase):
    def test_new_rows_are_added_to_ha_in_aisle_order_and_get_uids(self):
        shopping_list.add_item(self.conn, "milk", "Dairy & Eggs", amount="1", unit="gal")
        shopping_list.add_item(self.conn, "apples", "Produce")
        shopping_list.add_item(self.conn, "bread", "Bakery")

        counts = ha_sync.push(self.conn, self.ha)

        self.assertEqual(counts["added"], 3)
        self.assertEqual([i["summary"] for i in self.ha.items], ["apples", "1 gal milk", "bread"])
        self.assertEqual([i["description"] for i in self.ha.items],
                         ["Produce", "Dairy & Eggs", "Bakery"])
        rows = self.rows()
        self.assertEqual({rows["apples"]["ha_uid"], rows["milk"]["ha_uid"], rows["bread"]["ha_uid"]},
                         {"u1", "u2", "u3"})

    def test_second_pass_changes_nothing(self):
        shopping_list.add_item(self.conn, "milk", "Dairy & Eggs")
        ha_sync.push(self.conn, self.ha)
        self.ha.calls.clear()
        counts = ha_sync.push(self.conn, self.ha)
        self.assertEqual(sum(counts.values()), 0)
        self.assertEqual(self.ha.calls, ["get_items"])

    def test_app_changes_overwrite_ha(self):
        _, item_id = shopping_list.add_item(self.conn, "milk", "Dairy & Eggs")
        ha_sync.push(self.conn, self.ha)
        shopping_list.toggle_checked(self.conn, item_id)
        shopping_list.set_aisle(self.conn, item_id, "Beverages")

        counts = ha_sync.push(self.conn, self.ha)

        self.assertEqual(counts["updated"], 1)
        self.assertEqual(self.ha.items[0]["status"], "completed")
        self.assertEqual(self.ha.items[0]["description"], "Beverages")

    def test_deleting_in_the_app_removes_from_ha_instead_of_readopting(self):
        _, item_id = shopping_list.add_item(self.conn, "milk", "Dairy & Eggs")
        ha_sync.push(self.conn, self.ha)
        shopping_list.remove_item(self.conn, item_id)

        counts = ha_sync.push(self.conn, self.ha)

        self.assertEqual(counts["removed_remote"], 1)
        self.assertEqual(self.ha.items, [])
        self.assertEqual(self.rows(), {})
        self.assertEqual(shopping_list.tombstones(self.conn), set())

    def test_clearing_the_app_list_empties_ha(self):
        shopping_list.add_item(self.conn, "milk", "Dairy & Eggs")
        shopping_list.add_item(self.conn, "eggs", "Dairy & Eggs")
        ha_sync.push(self.conn, self.ha)
        shopping_list.clear(self.conn)

        ha_sync.push(self.conn, self.ha)

        self.assertEqual(self.ha.items, [])
        self.assertEqual(self.rows(), {})

    def test_item_deleted_in_ha_is_removed_locally(self):
        shopping_list.add_item(self.conn, "milk", "Dairy & Eggs")
        ha_sync.push(self.conn, self.ha)
        self.ha.user_delete("milk")

        counts = ha_sync.push(self.conn, self.ha)

        self.assertEqual(counts["removed_local"], 1)
        self.assertEqual(self.rows(), {})

    def test_item_added_in_ha_is_adopted_with_a_parsed_amount_and_guessed_aisle(self):
        uid = self.ha.user_add("two pounds of ground beef")

        counts = ha_sync.push(self.conn, self.ha)

        self.assertEqual(counts["adopted"], 1)
        row = self.rows()["ground beef"]
        self.assertEqual((row["amount"], row["unit"], row["ha_uid"]), ("2", "lb", uid))
        self.assertEqual(row["aisle"], "Meat & Seafood")
        # And the app then normalises HA's line to its own form.
        self.assertEqual(self.ha.items[0]["summary"], "2 lb ground beef")
        self.assertEqual(self.ha.items[0]["description"], "Meat & Seafood")

    def test_adopting_twice_does_not_duplicate_or_merge(self):
        self.ha.user_add("milk")
        ha_sync.push(self.conn, self.ha)
        ha_sync.push(self.conn, self.ha)
        self.assertEqual(len(shopping_list.sync_rows(self.conn)), 1)
        self.assertEqual(len(self.ha.items), 1)

    def test_pantry_rows_are_never_mirrored(self):
        self.conn.execute(
            'INSERT INTO "shopping_list_item" ("name", "aisle", "in_pantry") VALUES (?, ?, 1)',
            ("salt", "Spices & Baking"),
        )
        self.conn.commit()
        ha_sync.push(self.conn, self.ha)
        self.assertEqual(self.ha.items, [])

    def test_ha_order_is_left_alone_once_items_exist(self):
        # HA has no REST call to reorder items, so a list the phone has
        # rearranged is never "fixed" -- the app must not try.
        shopping_list.add_item(self.conn, "apples", "Produce")
        shopping_list.add_item(self.conn, "bread", "Bakery")
        ha_sync.push(self.conn, self.ha)
        self.ha.items.reverse()
        self.ha.calls.clear()

        counts = ha_sync.push(self.conn, self.ha)

        self.assertEqual(sum(counts.values()), 0)
        self.assertEqual(self.ha.calls, ["get_items"])
        self.assertEqual([i["summary"] for i in self.ha.items], ["bread", "apples"])

    def test_failure_leaves_the_database_untouched(self):
        shopping_list.add_item(self.conn, "milk", "Dairy & Eggs")
        self.ha.fail = True
        with self.assertRaises(ha_sync.HAError):
            ha_sync.push(self.conn, self.ha)
        self.assertIsNone(self.rows()["milk"]["ha_uid"])

    def test_same_name_different_units_are_two_ha_items(self):
        self.conn.execute(
            'INSERT INTO "shopping_list_item" ("name", "amount", "unit", "aisle") VALUES '
            '("flour", "2", "cup", "Spices & Baking"), ("flour", "1", "lb", "Spices & Baking")'
        )
        self.conn.commit()
        ha_sync.push(self.conn, self.ha)
        self.assertEqual(sorted(i["summary"] for i in self.ha.items), ["1 lb flour", "2 cup flour"])
        uids = {r["ha_uid"] for r in shopping_list.sync_rows(self.conn)}
        self.assertEqual(len(uids), 2)


class TestApplyFromHA(SyncCase):
    def _mirrored(self, *names):
        ids = {}
        for name in names:
            _, ids[name] = shopping_list.add_item(self.conn, name, "Produce")
        ha_sync.push(self.conn, self.ha)
        return ids

    def test_tick_in_ha_lands_in_the_app(self):
        self._mirrored("apples")
        self.ha.tick("apples")
        applied = ha_sync.apply_from_ha(self.conn, self.ha.get_items())
        self.assertEqual(applied, 1)
        self.assertEqual(self.rows()["apples"]["checked"], 1)

    def test_is_idempotent(self):
        self._mirrored("apples")
        self.ha.tick("apples")
        ha_sync.apply_from_ha(self.conn, self.ha.get_items())
        self.assertEqual(ha_sync.apply_from_ha(self.conn, self.ha.get_items()), 0)

    def test_rename_and_aisle_edit_in_ha(self):
        self._mirrored("apples")
        self.ha.items[0]["summary"] = "3 lb green apples"
        self.ha.items[0]["description"] = "Snacks"
        ha_sync.apply_from_ha(self.conn, self.ha.get_items())
        rows = self.rows()
        self.assertNotIn("apples", rows)
        row = rows["green apples"]
        self.assertEqual((row["amount"], row["unit"], row["aisle"]), ("3", "lb", "Snacks"))

    def test_new_and_deleted_items_in_ha(self):
        self._mirrored("apples", "bread")
        self.ha.user_delete("bread")
        self.ha.user_add("milk")
        ha_sync.apply_from_ha(self.conn, self.ha.get_items())
        self.assertEqual(set(self.rows()), {"apples", "milk"})

    def test_unpushed_app_rows_are_left_alone(self):
        self._mirrored("apples")
        shopping_list.add_item(self.conn, "bread", "Bakery")  # not pushed yet
        ha_sync.apply_from_ha(self.conn, self.ha.get_items())
        self.assertEqual(set(self.rows()), {"apples", "bread"})

    def test_claims_unmapped_rows_by_summary_instead_of_duplicating(self):
        # The race seen on 2026-09-15: HA's automation posted the list while
        # the first push was still adding items, before uids were recorded.
        shopping_list.add_item(self.conn, "flank steak", "Meat & Seafood", amount="1", unit="lb")
        shopping_list.add_item(self.conn, "onion", "Produce", amount="1", unit="lg")
        self.ha.add_item("1 lb flank steak", "Meat & Seafood")   # added by the push...
        self.ha.add_item("1 lg onion", "Produce")
        self.ha.tick("1 lb flank steak")                          # ...ticked on the phone

        applied = ha_sync.apply_from_ha(self.conn, self.ha.get_items())  # ...before mapping

        rows = self.rows()
        self.assertEqual(len(rows), 2, "no duplicate rows may be minted")
        self.assertEqual(rows["flank steak"]["ha_uid"], "u1")
        self.assertEqual(rows["onion"]["ha_uid"], "u2")
        self.assertEqual(rows["flank steak"]["checked"], 1)
        self.assertEqual(applied, 3, "two claims and one tick")
        # And the push that follows has nothing left to add.
        self.ha.calls.clear()
        counts = ha_sync.push(self.conn, self.ha)
        self.assertEqual(counts["added"], 0)
        self.assertEqual(counts["adopted"], 0)

    def test_push_and_webhook_do_not_interleave(self):
        # While a push holds the lock, a webhook call waits; by the time it
        # runs the rows are mapped, so it ticks rather than adopts.
        shopping_list.add_item(self.conn, "milk", "Dairy & Eggs")
        started = threading.Event()
        release = threading.Event()
        original_add = self.ha.add_item

        def slow_add(summary, description):
            original_add(summary, description)
            started.set()
            release.wait(5)

        self.ha.add_item = slow_add
        pusher = threading.Thread(target=lambda: ha_sync.push(db.get_connection(self.db_path), self.ha))
        pusher.start()
        started.wait(5)
        self.ha.tick("milk")
        posted = self.ha.get_items()  # what HA's automation would send now

        def webhook():
            conn = db.get_connection(self.db_path)
            try:
                ha_sync.apply_from_ha(conn, posted)
            finally:
                conn.close()

        hook = threading.Thread(target=webhook)
        hook.start()
        hook.join(0.3)
        self.assertTrue(hook.is_alive(), "webhook must wait for the push to finish")
        release.set()
        pusher.join(5)
        hook.join(5)
        rows = self.rows()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows["milk"]["checked"], 1)

    def test_items_without_uid_are_ignored(self):
        self._mirrored("apples")
        self.assertEqual(ha_sync.apply_from_ha(self.conn, [{"summary": "x"}, {"uid": None}]
                                               + self.ha.get_items()), 0)


class TestRoundTrip(SyncCase):
    def test_echo_of_the_apps_own_change_dies_out(self):
        _, item_id = shopping_list.add_item(self.conn, "apples", "Produce")
        ha_sync.push(self.conn, self.ha)
        shopping_list.toggle_checked(self.conn, item_id)
        ha_sync.push(self.conn, self.ha)
        # HA's automation now posts the list back.
        self.assertEqual(ha_sync.apply_from_ha(self.conn, self.ha.get_items()), 0)
        self.ha.calls.clear()
        ha_sync.push(self.conn, self.ha)
        self.assertEqual(self.ha.calls, ["get_items"])


class TestWorker(SyncCase):
    def test_run_once_records_success_and_failure(self):
        shopping_list.add_item(self.conn, "apples", "Produce")
        worker = ha_sync.SyncWorker(self.db_path, self.ha)
        worker.run_once()
        self.assertIsNotNone(worker.status()["last_synced"])
        self.assertIsNone(worker.status()["last_error"])
        self.assertEqual(len(self.ha.items), 1)

        self.ha.fail = True
        worker.run_once()
        self.assertIn("unreachable", worker.status()["last_error"])

    def test_requests_coalesce_and_the_thread_stops(self):
        passes = []
        original = ha_sync.push

        def slow_push(conn, link):
            passes.append(1)
            return original(conn, link)

        with mock.patch.object(ha_sync, "push", slow_push):
            worker = ha_sync.SyncWorker(self.db_path, self.ha, interval=60)
            for _ in range(10):
                worker.request()
            worker.start()
            deadline = threading.Event()
            deadline.wait(0.5)
            worker.stop()
            worker.join(timeout=5)
        self.assertFalse(worker.is_alive())
        # Startup pass + at most one coalesced pass for the ten requests.
        self.assertLessEqual(len(passes), 2)
        self.assertGreaterEqual(len(passes), 1)


class TestHALinkTransport(unittest.TestCase):
    def test_builds_the_service_call_and_parses_the_response(self):
        link = ha_sync.HALink("http://ha.local:8123/", "tok", "todo.x")
        captured = {}

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def read(self):
                return json.dumps({"service_response": {"todo.x": {"items": [
                    {"uid": "a", "summary": "milk", "status": "needs_action"}
                ]}}}).encode()

        def fake_urlopen(request, timeout):
            captured["url"] = request.full_url
            captured["auth"] = request.get_header("Authorization")
            captured["body"] = json.loads(request.data)
            return Response()

        with mock.patch("urllib.request.urlopen", fake_urlopen):
            items = link.get_items()
        self.assertEqual(captured["url"], "http://ha.local:8123/api/services/todo/get_items?return_response")
        self.assertEqual(captured["auth"], "Bearer tok")
        self.assertEqual(captured["body"], {"entity_id": "todo.x"})
        self.assertEqual(items, [{"uid": "a", "summary": "milk", "status": "needs_action", "description": ""}])

    def test_transport_and_http_errors_become_haerror(self):
        link = ha_sync.HALink("http://ha.local:8123", "tok")
        with mock.patch("urllib.request.urlopen", side_effect=urllib.error.URLError("nope")):
            with self.assertRaises(ha_sync.HAError):
                link.get_items()
        err = urllib.error.HTTPError("u", 401, "Unauthorized", {}, None)
        with mock.patch("urllib.request.urlopen", side_effect=err):
            with self.assertRaisesRegex(ha_sync.HAError, "401"):
                link.add_item("milk", "")


if __name__ == "__main__":
    unittest.main()
