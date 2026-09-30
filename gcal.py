"""Pushes a week of planned meals onto a Google calendar.

Deliberately **one way**. The family calendar is the family's: they add
dentist appointments and school concerts to it, move things around, and
delete what they like. This app only ever writes, and only ever touches
events whose ids it recorded itself in the `gcal_event` table. Nothing on
the calendar is read back into the meal plan, so there is no reconciliation,
no duplicate detection and no way for a stray edit to rewrite the library.

Pressing "Send this week" means *make Google match my plan for this week*:
meals added since last time are created, meals whose recipe or servings
changed are updated, and meals removed from the plan take their event with
them. Pressing it twice in a row does nothing the second time.

Authentication is a Google Cloud **service account**, not OAuth. The
calendar is shared with the service account's email address, exactly as it
would be with a person, and given "Make changes to events". That avoids the
consent screen entirely -- and avoids the trap that an OAuth app left in
"Testing" hands out refresh tokens which expire after seven days, so the
link would break every week.
"""
import base64
import hashlib
import json
import sqlite3
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta
# Aliased: the stdlib `time` module is used for token expiry just below,
# and an unaliased import here silently shadows it.
from datetime import time as time_of_day

import meal_calendar

TOKEN_URL = "https://oauth2.googleapis.com/token"
API_ROOT = "https://www.googleapis.com/calendar/v3"
SCOPE = "https://www.googleapis.com/auth/calendar.events"

CALENDAR_ID_ENV = "MEAL_PLANNER_GCAL_ID"
# Two ways to sign in, and only one is ever configured at a time:
#   * a service account key file -- a robot the calendar is shared with
#   * your own Google account, via a refresh token from set_gcal_oauth.py
CREDENTIALS_ENV = "MEAL_PLANNER_GCAL_CREDENTIALS"
KEY_FILENAME = "google-calendar-key.json"
CLIENT_ID_ENV = "MEAL_PLANNER_GCAL_CLIENT_ID"
CLIENT_SECRET_ENV = "MEAL_PLANNER_GCAL_CLIENT_SECRET"
REFRESH_TOKEN_ENV = "MEAL_PLANNER_GCAL_REFRESH_TOKEN"

# When each meal lands on the calendar. Times, not all-day banners, so a
# glance at the day shows dinner in the evening where it belongs -- but
# marked free (see build_event), so they never look like commitments that
# block the day for anyone reading the shared calendar.
SLOT_TIMES = {
    "Breakfast": time_of_day(7, 0),
    "Lunch": time_of_day(12, 0),
    "Dinner": time_of_day(18, 0),
}
DEFAULT_SLOT_TIME = time_of_day(12, 0)
MEAL_DURATION = timedelta(hours=1)


class GCalError(Exception):
    """Anything that stopped us writing to the calendar."""


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


class ServiceAccount:
    """A service-account key file, and the access tokens it can mint.

    Google's flow here is a "JWT bearer grant": sign a short assertion with
    the key's private half, swap it for an access token good for an hour.
    We cache the token and re-mint it a minute before it expires.
    """

    def __init__(self, info: dict, timeout: float = 10.0):
        missing = [k for k in ("client_email", "private_key", "token_uri") if not info.get(k)]
        if missing:
            raise GCalError(f"that key file is missing {', '.join(missing)} -- is it a service account key?")
        self.client_email = info["client_email"]
        self._private_key_pem = info["private_key"]
        self._token_uri = info.get("token_uri", TOKEN_URL)
        self.timeout = timeout
        self._token = ""
        self._expires_at = 0.0

    @classmethod
    def from_file(cls, path, timeout: float = 10.0) -> "ServiceAccount":
        try:
            with open(path, "r", encoding="utf-8") as handle:
                info = json.load(handle)
        except FileNotFoundError as exc:
            raise GCalError(f"no service account key at {path}") from exc
        except (OSError, ValueError) as exc:
            raise GCalError(f"could not read {path}: {exc}") from exc
        return cls(info, timeout=timeout)

    def _sign(self, message: bytes) -> bytes:
        # Imported here, not at module scope: everything else in the app
        # works without cryptography installed, and only this path needs it.
        try:
            from cryptography.hazmat.primitives import hashes, serialization
            from cryptography.hazmat.primitives.asymmetric import padding
        except ImportError as exc:  # pragma: no cover - depends on the install
            raise GCalError(
                "the cryptography package is needed to sign in to Google; "
                "run: pip install -r requirements.txt"
            ) from exc
        key = serialization.load_pem_private_key(self._private_key_pem.encode("utf-8"), password=None)
        return key.sign(message, padding.PKCS1v15(), hashes.SHA256())

    def access_token(self) -> str:
        if self._token and time.time() < self._expires_at:
            return self._token
        now = int(time.time())
        header = {"alg": "RS256", "typ": "JWT"}
        claims = {
            "iss": self.client_email,
            "scope": SCOPE,
            "aud": self._token_uri,
            "iat": now,
            "exp": now + 3600,
        }
        signing_input = ".".join(
            _b64url(json.dumps(part, separators=(",", ":")).encode("utf-8"))
            for part in (header, claims)
        ).encode("ascii")
        assertion = signing_input.decode("ascii") + "." + _b64url(self._sign(signing_input))

        body = urllib.parse.urlencode({
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": assertion,
        }).encode("ascii")
        request = urllib.request.Request(
            self._token_uri, data=body, method="POST",
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as exc:
            detail = _http_detail(exc)
            raise GCalError(f"Google refused the service account key ({exc.code}){detail}") from exc
        except (urllib.error.URLError, OSError) as exc:
            raise GCalError(f"could not reach Google: {exc}") from exc

        token = payload.get("access_token")
        if not token:
            raise GCalError("Google returned no access token")
        self._token = token
        self._expires_at = time.time() + float(payload.get("expires_in", 3600)) - 60
        return token


class OAuthUser:
    """Your own Google account, via a refresh token.

    The same shape as ServiceAccount -- both exist only to hand GCalLink a
    live access token -- so nothing else in this module cares which is in
    use. Access tokens last an hour; the refresh token is the durable part
    and is what set_gcal_oauth.py stores.
    """

    def __init__(self, client_id: str, client_secret: str, refresh_token: str,
                 timeout: float = 10.0):
        if not (client_id and client_secret and refresh_token):
            raise GCalError("incomplete Google sign-in details; re-run set_gcal_oauth.py")
        self.client_id = client_id
        self.client_secret = client_secret
        self.refresh_token = refresh_token
        self.timeout = timeout
        self._token = ""
        self._expires_at = 0.0

    def access_token(self) -> str:
        if self._token and time.time() < self._expires_at:
            return self._token
        body = urllib.parse.urlencode({
            "client_id": self.client_id,
            "client_secret": self.client_secret,
            "refresh_token": self.refresh_token,
            "grant_type": "refresh_token",
        }).encode("ascii")
        request = urllib.request.Request(
            TOKEN_URL, data=body, method="POST",
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as exc:
            detail = _http_detail(exc)
            raise GCalError(
                f"Google would not renew the sign-in ({exc.code}){detail}. "
                "If this says invalid_grant, the refresh token was revoked or expired -- "
                "run set_gcal_oauth.py again."
            ) from exc
        except (urllib.error.URLError, OSError) as exc:
            raise GCalError(f"could not reach Google: {exc}") from exc
        token = payload.get("access_token")
        if not token:
            raise GCalError("Google returned no access token")
        self._token = token
        self._expires_at = time.time() + float(payload.get("expires_in", 3600)) - 60
        return token


def _http_detail(exc: urllib.error.HTTPError) -> str:
    """Google's errors carry a useful message; surface it rather than a bare code."""
    try:
        body = json.loads(exc.read() or b"{}")
    except Exception:
        return ""
    message = ""
    if isinstance(body.get("error"), dict):
        message = body["error"].get("message", "")
    elif isinstance(body.get("error"), str):
        message = body.get("error_description") or body["error"]
    return f": {message}" if message else ""


class GCalLink:
    """The four Calendar API calls a one-way push needs."""

    def __init__(self, calendar_id: str, credentials: ServiceAccount, timeout: float = 10.0):
        self.calendar_id = calendar_id
        self.credentials = credentials
        self.timeout = timeout

    def _request(self, method: str, path: str, payload: dict | None = None) -> dict:
        url = f"{API_ROOT}{path}"
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        request = urllib.request.Request(
            url, data=data, method=method,
            headers={
                "Authorization": f"Bearer {self.credentials.access_token()}",
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            raise GCalHTTPError(exc.code, f"Google answered {exc.code}{_http_detail(exc)}") from exc
        except (urllib.error.URLError, OSError) as exc:
            raise GCalError(f"could not reach Google: {exc}") from exc
        return json.loads(raw or b"{}")

    def _calendar_path(self, suffix: str = "") -> str:
        return f"/calendars/{urllib.parse.quote(self.calendar_id, safe='')}/events{suffix}"

    def check(self) -> str:
        """Prove we can reach the calendar, before saving anything.

        Deliberately events.list rather than calendars.get: the only scope
        this app asks for is calendar.events, which does not authorise
        reading calendar metadata -- calendars.get answers 403 even when
        everything is set up correctly. The events listing carries the
        calendar's title anyway, so nothing is lost.
        """
        payload = self._request("GET", self._calendar_path() + "?maxResults=1")
        return payload.get("summary") or self.calendar_id

    def insert_event(self, event: dict) -> str:
        return self._request("POST", self._calendar_path(), event)["id"]

    def update_event(self, event_id: str, event: dict) -> None:
        self._request("PATCH", self._calendar_path(f"/{urllib.parse.quote(event_id)}"), event)

    def delete_event(self, event_id: str) -> None:
        self._request("DELETE", self._calendar_path(f"/{urllib.parse.quote(event_id)}"))


class GCalHTTPError(GCalError):
    """An HTTP error, keeping the status so 404/410 can be treated as "already gone"."""

    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


def _slot_window(slot: str, day: str) -> tuple[str, str]:
    """Start and end of a meal, as RFC 3339 stamps carrying a UTC offset.

    The offset is worked out for that particular date in the machine's own
    timezone, so meals on either side of a daylight-saving change each get
    the right one -- which a fixed offset would not.
    """
    start_local = datetime.combine(
        date.fromisoformat(day), SLOT_TIMES.get(slot, DEFAULT_SLOT_TIME)
    ).astimezone()
    return start_local.isoformat(), (start_local + MEAL_DURATION).isoformat()


def format_ingredients(ingredients: list) -> list:
    """The recipe's ingredients as plain lines, sections kept.

    Same shape as the recipe page -- "2 cups flour" -- so the calendar
    entry reads like the recipe rather than like a database row.
    """
    lines = []
    section = None
    for row in ingredients:
        if row["section"] and row["section"] != section:
            section = row["section"]
            lines.append("")
            lines.append(f"{section}:")
        parts = [str(row["amount"]).strip() if row["amount"] else "",
                 str(row["unit"]).strip() if row["unit"] else ""]
        measure = " ".join(part for part in parts if part)
        lines.append(f"  - {measure} {row['name']}".rstrip() if measure else f"  - {row['name']}")
    return lines


def build_event(slot: str, meal: dict, day: str, app_base_url: str = "",
                ingredients: list | None = None) -> dict:
    """One planned meal, as a timed event the family can read at a glance."""
    summary = f"{slot}: {meal['recipe_name']}"
    lines = []
    if meal.get("servings"):
        lines.append(f"Servings: {meal['servings']}")
    if ingredients:
        if lines:
            lines.append("")
        lines.append("Ingredients:")
        lines.extend(format_ingredients(ingredients))
    if app_base_url:
        lines.append("")
        lines.append(f"{app_base_url.rstrip('/')}/recipes/{meal['recipe_id']}")
    lines.append("")
    lines.append("Added by Meal Planner.")
    start, end = _slot_window(slot, day)
    return {
        "summary": summary,
        "description": "\n".join(lines),
        "start": {"dateTime": start},
        "end": {"dateTime": end},
        # Free, not busy: nobody's calendar should show them as unavailable
        # at 6pm because dinner is planned.
        "transparency": "transparent",
    }


def ingredients_for(conn: sqlite3.Connection, recipe_id: int) -> list:
    return conn.execute(
        'SELECT "name", "amount", "unit", "section" FROM "recipe_ingredient" '
        'WHERE "recipe_id" = ? ORDER BY "order_num"',
        (recipe_id,),
    ).fetchall()


def content_hash(event: dict) -> str:
    return hashlib.sha256(
        json.dumps(event, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


class PushResult:
    def __init__(self):
        self.added = 0
        self.updated = 0
        self.removed = 0
        self.unchanged = 0

    @property
    def changed(self) -> int:
        return self.added + self.updated + self.removed

    def summary(self) -> str:
        if not self.changed:
            return "Google calendar was already up to date."
        bits = []
        for count, word in ((self.added, "added"), (self.updated, "updated"), (self.removed, "removed")):
            if count:
                bits.append(f"{count} {word}")
        return "Google calendar updated: " + ", ".join(bits) + "."

    def as_dict(self) -> dict:
        return {
            "added": self.added, "updated": self.updated,
            "removed": self.removed, "unchanged": self.unchanged,
        }


def push_week(conn: sqlite3.Connection, link: GCalLink, week_start: date,
              app_base_url: str = "") -> PushResult:
    """Make the calendar match this week's plan. Only touches our own events."""
    plan = meal_calendar.get_week_plan(conn, week_start)
    known = {
        (row["date"], row["slot"]): (row["event_id"], row["content_hash"])
        for row in conn.execute(
            'SELECT "date", "slot", "event_id", "content_hash" FROM "gcal_event" WHERE "calendar_id" = ?',
            (link.calendar_id,),
        )
    }
    result = PushResult()

    for (day, slot), meal in sorted(plan.items()):
        recorded = known.get((day, slot))
        if meal is None:
            if recorded:
                _forget(conn, link, day, slot, recorded[0])
                result.removed += 1
            continue
        event = build_event(
            slot, meal, day, app_base_url, ingredients_for(conn, meal["recipe_id"])
        )
        digest = content_hash(event)
        if recorded and recorded[1] == digest:
            result.unchanged += 1
            continue
        if recorded:
            try:
                link.update_event(recorded[0], event)
            except GCalHTTPError as exc:
                # Deleted on the calendar by hand. Their call, not an error:
                # put the meal back as a new event rather than resurrecting.
                if exc.status not in (404, 410):
                    raise
                _record(conn, link, day, slot, link.insert_event(event), digest)
                result.added += 1
                continue
            _record(conn, link, day, slot, recorded[0], digest)
            result.updated += 1
        else:
            _record(conn, link, day, slot, link.insert_event(event), digest)
            result.added += 1

    # get_week_plan returns an entry for every day/slot in the week, empty
    # ones included, so a meal removed since the last push is seen here as
    # `meal is None` with a recorded event -- and deleted. Rows for other
    # weeks are not in `plan` and are deliberately left alone.
    conn.commit()
    return result


def _record(conn: sqlite3.Connection, link: GCalLink, day: str, slot: str,
            event_id: str, digest: str) -> None:
    conn.execute(
        '''INSERT INTO "gcal_event" ("calendar_id", "date", "slot", "event_id", "content_hash")
           VALUES (?, ?, ?, ?, ?)
           ON CONFLICT("calendar_id", "date", "slot")
           DO UPDATE SET "event_id" = excluded."event_id", "content_hash" = excluded."content_hash"''',
        (link.calendar_id, day, slot, event_id, digest),
    )


def _forget(conn: sqlite3.Connection, link: GCalLink, day: str, slot: str, event_id: str) -> None:
    try:
        link.delete_event(event_id)
    except GCalHTTPError as exc:
        # Already gone from the calendar: nothing to do but stop tracking it.
        if exc.status not in (404, 410):
            raise
    conn.execute(
        'DELETE FROM "gcal_event" WHERE "calendar_id" = ? AND "date" = ? AND "slot" = ?',
        (link.calendar_id, day, slot),
    )


def stored_key_path():
    """Where a service account key is kept: with the app's own data, beside
    the recipes, rather than wherever the browser downloaded it.

    Imported lazily so `import gcal` stays cheap and paths-free for tests.
    """
    import paths
    return paths.data_dir() / KEY_FILENAME


def credentials_from_env(env: dict):
    """Whichever sign-in is configured, or None.

    Your own account wins if both are present: it is the one you chose most
    recently, and leaving a stale service-account key in the file should not
    silently keep using the robot.
    """
    refresh_token = (env.get(REFRESH_TOKEN_ENV) or "").strip()
    if refresh_token:
        return OAuthUser(
            (env.get(CLIENT_ID_ENV) or "").strip(),
            (env.get(CLIENT_SECRET_ENV) or "").strip(),
            refresh_token,
        )
    key_path = (env.get(CREDENTIALS_ENV) or "").strip()
    if key_path:
        return ServiceAccount.from_file(key_path)
    return None


def link_from_env(env: dict) -> GCalLink | None:
    """Build a link from MEAL_PLANNER_GCAL_* settings, or None if not set up."""
    calendar_id = (env.get(CALENDAR_ID_ENV) or "").strip()
    if not calendar_id:
        return None
    credentials = credentials_from_env(env)
    if credentials is None:
        return None
    return GCalLink(calendar_id, credentials)
