"""Two-way mirror of the shopping list into a Home Assistant to-do list.

The app's database is the source of truth; the HA to-do entity is a
copy the household can tick, add to and delete from on a phone through
Nabu Casa. Both directions are idempotent reconciliations rather than
event replay, so a change that echoes back (app writes HA -> HA's
automation posts the list -> the webhook finds nothing to change) dies
after one round trip. See docs/superpowers/specs/2026-09-14-remote-access-design.md.

    push(conn, link)            app -> HA   (app wins; also adopts HA-only items)
    apply_from_ha(conn, items)  HA -> app   (HA wins; the webhook's half)
    SyncWorker                  background thread that runs push() on
                                request (coalesced) and every few minutes

Every HA call goes through HALink so tests can stand in a fake.
"""
import json
import logging
import queue
import threading
import urllib.error
import urllib.request
from datetime import datetime

import db
import grocery_categories
import shopping_list
import unit_conversion
import voice

log = logging.getLogger(__name__)

DEFAULT_ENTITY = "todo.shopping_list"
PERIODIC_SECONDS = 300
_AISLE_RANK = {name: i for i, name in enumerate(grocery_categories.AISLE_ORDER)}

# One reconcile at a time. The worker pushes from its own thread while HA's
# automation can post to the webhook on a request thread at any moment --
# including mid-push, after items were added to HA but before their uids
# were recorded here. Serialising the two keeps that webhook call waiting
# until the rows are mapped, so it can't adopt the app's own items as new.
_RECONCILE_LOCK = threading.Lock()


class HAError(Exception):
    """Home Assistant could not be reached or refused the call."""


class HALink:
    """The handful of Home Assistant REST calls the mirror needs."""

    def __init__(self, base_url: str, token: str, entity_id: str = DEFAULT_ENTITY, timeout: float = 5.0):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.entity_id = entity_id
        self.timeout = timeout

    def call_service(self, service: str, data: dict, return_response: bool = False) -> dict:
        url = f"{self.base_url}/api/services/todo/{service}"
        if return_response:
            url += "?return_response"
        body = json.dumps({"entity_id": self.entity_id, **data}).encode("utf-8")
        request = urllib.request.Request(
            url, data=body, method="POST",
            headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            raise HAError(f"Home Assistant answered {exc.code} to todo.{service}") from exc
        except (urllib.error.URLError, OSError) as exc:
            raise HAError(f"Home Assistant unreachable: {exc}") from exc
        return json.loads(raw or b"{}")

    def get_items(self) -> list:
        """HA's items, in HA's order: [{uid, summary, status, description}, ...]."""
        result = self.call_service("get_items", {}, return_response=True)
        items = result.get("service_response", {}).get(self.entity_id, {}).get("items", [])
        return [
            {
                "uid": item.get("uid"),
                "summary": item.get("summary", ""),
                "status": item.get("status", "needs_action"),
                "description": item.get("description") or "",
            }
            for item in items
        ]

    def add_item(self, summary: str, description: str) -> None:
        data = {"item": summary}
        if description:
            data["description"] = description
        self.call_service("add_item", data)

    def update_item(self, uid: str, *, rename=None, status=None, description=None) -> None:
        data = {"item": uid}
        if rename is not None:
            data["rename"] = rename
        if status is not None:
            data["status"] = status
        if description is not None:
            data["description"] = description
        self.call_service("update_item", data)

    def remove_items(self, uids: list) -> None:
        if uids:
            self.call_service("remove_item", {"item": list(uids)})


# ---------------------------------------------------------------- item text

def build_summary(name: str, amount, unit) -> str:
    """The one line HA shows: "2 lb ground beef", "eggs"."""
    return " ".join(str(p) for p in (amount, unit, name) if p)


def parse_summary(summary: str) -> tuple:
    """Inverse of build_summary for text typed or spoken into HA. Returns
    (name, amount, unit); amount/unit are None when the line doesn't
    start with a quantity. "two pounds of ground beef" -> ("ground beef",
    "2", "lb"); "bring the good olive oil" -> (that, None, None)."""
    words = summary.strip().split()
    if not words:
        return ("", None, None)

    amount = None
    consumed = 0
    # Two-word amounts first: "a dozen", "1 1/2". Then a single word or
    # number: "two", "2", "1/2", "half".
    if len(words) >= 2:
        pair = " ".join(words[:2])
        spoken = voice.normalize_quantity(pair)
        numeric = unit_conversion.parse_amount(pair) if "/" in words[1] else None
        if spoken is not None:
            amount, consumed = spoken, 2
        elif numeric is not None:
            amount, consumed = unit_conversion.format_amount(numeric), 2
    if amount is None:
        single = voice.normalize_quantity(words[0])
        if single is not None:
            amount, consumed = single, 1

    unit = None
    if amount is not None and consumed < len(words):
        candidate = words[consumed]
        if unit_conversion.is_known_unit_word(candidate):
            unit = unit_conversion.normalize_unit(candidate)
            consumed += 1
        if consumed < len(words) and words[consumed].lower() == "of":
            consumed += 1

    name = " ".join(words[consumed:]).strip()
    if not name:
        # "2" or "2 lb" alone is not an item; keep the text as the name.
        return (summary.strip(), None, None)
    return (name, amount, unit)


def _status(checked) -> str:
    return "completed" if checked else "needs_action"


def _aisle_key(row) -> tuple:
    aisle = row["aisle"] or ""
    return (_AISLE_RANK.get(aisle, len(_AISLE_RANK)), aisle.lower(), (row["name"] or "").lower())


# ---------------------------------------------------------------- app -> HA

def push(conn, link: HALink) -> dict:
    """Make HA's list agree with the app's (app wins), adopting anything
    that exists only in HA. Returns counts for logging/tests."""
    with _RECONCILE_LOCK:
        return _push(conn, link)


def _push(conn, link: HALink) -> dict:
    counts = {"added": 0, "updated": 0, "removed_local": 0, "removed_remote": 0, "adopted": 0}
    ha_items = link.get_items()
    ha_by_uid = {item["uid"]: item for item in ha_items if item["uid"]}

    # Deleted (or cleared) in the app since the last pass: drop the HA copy
    # rather than adopting it back as "new in HA".
    dead = shopping_list.tombstones(conn)
    to_remove = [uid for uid in dead if uid in ha_by_uid]
    if to_remove:
        link.remove_items(to_remove)
        counts["removed_remote"] = len(to_remove)
        for uid in to_remove:
            ha_by_uid.pop(uid, None)
        ha_items = [item for item in ha_items if item["uid"] not in set(to_remove)]
    if dead:
        shopping_list.forget_tombstones(conn, dead)

    rows = shopping_list.sync_rows(conn)
    mapped = {row["ha_uid"]: row for row in rows if row["ha_uid"]}

    # Deleted in HA since the last pass: a mapped uid HA no longer has.
    for uid, row in mapped.items():
        if uid not in ha_by_uid:
            shopping_list.remove_item_from_ha(conn, row["id"])
            counts["removed_local"] += 1

    # Created in HA (typed into the card, or spoken to an assistant).
    for uid, item in ha_by_uid.items():
        if uid not in mapped:
            _adopt(conn, item)
            counts["adopted"] += 1

    # New rows go in aisle order. Home Assistant offers no REST call to
    # reorder existing items (todo/item/move is WebSocket-only), so a list
    # that grows over several passes can drift from aisle order; each item
    # still shows its aisle on its second line.
    rows = shopping_list.sync_rows(conn)
    unmapped = [row for row in rows if not row["ha_uid"]]
    if unmapped:
        for row in sorted(unmapped, key=_aisle_key):
            link.add_item(build_summary(row["name"], row["amount"], row["unit"]), row["aisle"] or "")
            counts["added"] += 1
        # HA doesn't return the new uid; find each by summary among the
        # uids we don't know yet.
        known = {row["ha_uid"] for row in rows if row["ha_uid"]}
        fresh = [item for item in link.get_items() if item["uid"] and item["uid"] not in known]
        for row in unmapped:
            summary = build_summary(row["name"], row["amount"], row["unit"])
            match = next((item for item in fresh if item["summary"] == summary), None)
            if match is not None:
                fresh.remove(match)
                shopping_list.set_ha_uid(conn, row["id"], match["uid"])
        rows = shopping_list.sync_rows(conn)
        ha_by_uid = {item["uid"]: item for item in link.get_items() if item["uid"]}

    # Rows HA has but that drifted (app wins).
    for row in rows:
        item = ha_by_uid.get(row["ha_uid"]) if row["ha_uid"] else None
        if item is None:
            continue
        summary = build_summary(row["name"], row["amount"], row["unit"])
        aisle = row["aisle"] or ""
        status = _status(row["checked"])
        changes = {}
        if item["summary"] != summary:
            changes["rename"] = summary
        if item["description"] != aisle:
            changes["description"] = aisle
        if item["status"] != status:
            changes["status"] = status
        if changes:
            link.update_item(row["ha_uid"], **changes)
            counts["updated"] += 1
    return counts


def _adopt(conn, item: dict) -> int:
    name, amount, unit = parse_summary(item["summary"])
    if not name:
        return 0
    return shopping_list.insert_from_ha(
        conn, name, amount, unit, item["description"] or None,
        checked=item["status"] == "completed", ha_uid=item["uid"],
    )


# ---------------------------------------------------------------- HA -> app

def apply_from_ha(conn, items: list) -> int:
    """The webhook's half: HA's full list arrives; HA wins for every item
    it knows about. Rows the app hasn't pushed yet (ha_uid NULL) are left
    for the worker. Returns how many rows changed. Idempotent."""
    with _RECONCILE_LOCK:
        return _apply_from_ha(conn, items)


def _apply_from_ha(conn, items: list) -> int:
    applied = 0
    rows = shopping_list.sync_rows(conn)
    mapped = {row["ha_uid"]: row for row in rows if row["ha_uid"]}
    # Rows pushed to HA whose uid we never learned (a push cut short, or
    # the app restarted mid-pass): claim them by summary rather than
    # adopting HA's copy as a second row.
    unclaimed = {}
    for row in rows:
        if not row["ha_uid"]:
            unclaimed.setdefault(build_summary(row["name"], row["amount"], row["unit"]), row)
    seen = set()
    for item in items:
        uid = item.get("uid")
        if not uid:
            continue
        seen.add(uid)
        summary = item.get("summary", "") or ""
        description = item.get("description") or ""
        checked = item.get("status") == "completed"
        row = mapped.get(uid)
        if row is None and summary in unclaimed:
            row = unclaimed.pop(summary)
            shopping_list.set_ha_uid(conn, row["id"], uid)
            mapped[uid] = row
            applied += 1
        if row is None:
            if _adopt(conn, {"uid": uid, "summary": summary, "status": item.get("status"),
                             "description": description}):
                applied += 1
            continue
        changed = False
        if bool(row["checked"]) != checked:
            shopping_list.set_checked(conn, row["id"], checked)
            changed = True
        if summary != build_summary(row["name"], row["amount"], row["unit"]):
            name, amount, unit = parse_summary(summary)
            if name:
                shopping_list.update_fields(conn, row["id"], name=name, amount=amount, unit=unit)
                changed = True
        if description != (row["aisle"] or ""):
            shopping_list.set_aisle(conn, row["id"], description)
            changed = True
        applied += changed
    for uid, row in mapped.items():
        if uid not in seen:
            shopping_list.remove_item_from_ha(conn, row["id"])
            applied += 1
    return applied


# ---------------------------------------------------------------- worker

class SyncWorker(threading.Thread):
    """Runs push() in the background: whenever the app changes the list
    (many changes in a burst coalesce into one pass) and every
    PERIODIC_SECONDS regardless, so HA edits that fired no automation
    and pushes that failed both get repaired."""

    def __init__(self, db_path, link: HALink, interval: float = PERIODIC_SECONDS):
        super().__init__(name="ha-sync", daemon=True)
        self.db_path = db_path
        self.link = link
        self.interval = interval
        self._wake = queue.Queue(maxsize=1)
        # Not `_stop`: threading.Thread has a private _stop() method that
        # join() calls on Python 3.11, and shadowing it crashes the join.
        self._stopping = threading.Event()
        self._lock = threading.Lock()
        self.last_synced = None
        self.last_error = None
        self.last_error_at = None

    def request(self) -> None:
        """Ask for a pass soon. Non-blocking; a pending request is enough."""
        try:
            self._wake.put_nowait("push")
        except queue.Full:
            pass

    def stop(self) -> None:
        self._stopping.set()
        self.request()

    def run_once(self) -> None:
        conn = db.get_connection(self.db_path)
        try:
            push(conn, self.link)
            with self._lock:
                self.last_synced = datetime.now()
                self.last_error = None
        except HAError as exc:
            with self._lock:
                if self.last_error is None:
                    log.warning("Home Assistant sync failed: %s", exc)
                self.last_error = str(exc)
                self.last_error_at = datetime.now()
        except Exception:  # noqa: BLE001 -- a bug must not kill the thread
            log.exception("Home Assistant sync crashed")
            with self._lock:
                self.last_error = "internal error (see log)"
                self.last_error_at = datetime.now()
        finally:
            conn.close()

    def run(self) -> None:
        self.run_once()
        while not self._stopping.is_set():
            try:
                self._wake.get(timeout=self.interval)
            except queue.Empty:
                pass
            if self._stopping.is_set():
                break
            self.run_once()

    def status(self) -> dict:
        with self._lock:
            return {
                "last_synced": self.last_synced,
                "last_error": self.last_error,
                "last_error_at": self.last_error_at,
            }
