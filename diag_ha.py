"""Diagnostic for the Home Assistant shopping-list mirror.

Prints the app's shopping rows (with their HA uids) and what HA's
to-do list holds, so a sync problem can be seen from both sides. Reads
only, unless asked to repair:

    python diag_ha.py
    python diag_ha.py --repair-duplicates

--repair-duplicates fixes rows that share one HA uid (two app rows
mirrored to the same HA item): the oldest row is kept, a tick on any
of the others is carried onto it, and the others are deleted. Stop the
app first so a sync pass doesn't run in the middle.
"""
import os
import sys

from dotenv import load_dotenv

import db
import ha_sync
import paths
import shopping_list

load_dotenv(paths.env_path())
url, token = os.environ.get("MEAL_PLANNER_HA_URL"), os.environ.get("MEAL_PLANNER_HA_TOKEN")
entity = os.environ.get("MEAL_PLANNER_HA_TODO_ENTITY") or ha_sync.DEFAULT_ENTITY
if not (url and token):
    sys.exit("No HA link in .env -- run set_ha_link.py first.")

conn = db.get_connection(paths.db_path())

if "--repair-duplicates" in sys.argv:
    groups = conn.execute(
        'SELECT "ha_uid", COUNT(*) AS n FROM "shopping_list_item" '
        'WHERE "ha_uid" IS NOT NULL GROUP BY "ha_uid" HAVING n > 1'
    ).fetchall()
    removed = 0
    for group in groups:
        rows = conn.execute(
            'SELECT "id", "name", "checked" FROM "shopping_list_item" WHERE "ha_uid" = ? ORDER BY "id"',
            (group["ha_uid"],),
        ).fetchall()
        keep, extras = rows[0], rows[1:]
        if any(r["checked"] for r in extras) and not keep["checked"]:
            conn.execute('UPDATE "shopping_list_item" SET "checked" = 1 WHERE "id" = ?', (keep["id"],))
        conn.execute(
            'DELETE FROM "shopping_list_item" WHERE "ha_uid" = ? AND "id" != ?',
            (group["ha_uid"], keep["id"]),
        )
        removed += len(extras)
        print(f"  kept #{keep['id']} {keep['name']!r}, removed {len(extras)} duplicate(s)")
    conn.commit()
    print(f"Repaired {len(groups)} item(s), removed {removed} duplicate row(s).")
    print()

print(f"== App rows ({paths.db_path()}) ==")
for row in shopping_list.sync_rows(conn):
    print(f"  id={row['id']:<4} checked={row['checked']} amount={row['amount']!r:<8} "
          f"unit={row['unit']!r:<8} name={row['name']!r} aisle={row['aisle']!r} ha_uid={row['ha_uid']!r}")
print(f"  tombstones: {sorted(shopping_list.tombstones(conn))}")
conn.close()

print(f"\n== HA items ({entity} at {url}) ==")
link = ha_sync.HALink(url, token, entity)
try:
    raw = link.call_service("get_items", {}, return_response=True)
except ha_sync.HAError as exc:
    sys.exit(f"  {exc}")
print(f"  raw keys: {list(raw)}; service_response keys: {list(raw.get('service_response', {}))}")
for item in link.get_items():
    print(f"  uid={item['uid']!r} status={item['status']:<12} summary={item['summary']!r} "
          f"description={item['description']!r}")
