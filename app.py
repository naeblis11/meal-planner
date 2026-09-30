"""Flask app for browsing the Open Recipe Format recipe library."""
import hmac
import io
import json
import logging
import os
import re
import secrets
import shutil
import sys
import uuid
from datetime import date, datetime, timedelta
from pathlib import Path

import yaml
from flask import (
    Flask, abort, flash, g, redirect, render_template, request, send_from_directory, session,
    url_for,
)
from flask.sessions import SecureCookieSessionInterface
from PIL import Image, ImageOps, UnidentifiedImageError
from werkzeug.middleware.proxy_fix import ProxyFix
from werkzeug.security import check_password_hash

import db
import grocery_categories
import gcal
import ha_sync
import meal_calendar
import meal_master
import pantry
import paths
import recipe_extraction
import recipe_sync
import shopping_list
import unit_conversion
import voice


RECIPE_IMAGE_DETAIL_MAX = 800
RECIPE_IMAGE_THUMB_SIZE = 96


def _thumb_filename(image_filename: str) -> str:
    return image_filename.replace(".jpg", "_thumb.jpg")


def _process_and_save_recipe_image(source_stream, images_dir: Path, recipe_uuid: str) -> str:
    """Loads an image from `source_stream` (any file-like object Pillow's
    Image.open accepts), saves an 800px-capped detail JPEG and a 96x96
    center-cropped thumbnail JPEG under `images_dir`, named from
    `recipe_uuid`. Returns the detail image's filename. Raises
    PIL.UnidentifiedImageError/OSError for invalid image data -- the
    caller decides how to handle that."""
    source = Image.open(source_stream)
    source.load()
    source = ImageOps.exif_transpose(source).convert("RGB")

    images_dir.mkdir(parents=True, exist_ok=True)
    image_filename = f"{recipe_uuid}.jpg"

    detail = source.copy()
    detail.thumbnail((RECIPE_IMAGE_DETAIL_MAX, RECIPE_IMAGE_DETAIL_MAX))
    detail.save(images_dir / image_filename, "JPEG", quality=85)

    thumb = ImageOps.fit(source, (RECIPE_IMAGE_THUMB_SIZE, RECIPE_IMAGE_THUMB_SIZE))
    thumb.save(images_dir / _thumb_filename(image_filename), "JPEG", quality=80)

    return image_filename


DEFAULT_CATEGORIES = [
    "Appetizers", "Soups & Stews", "Salads", "Main Dishes", "Side Dishes",
    "Breads & Baking", "Desserts", "Beverages", "Sauces & Condiments",
    "Breakfast",
]
DEFAULT_SUBCATEGORIES = ["Beef", "Pork", "Chicken", "Turkey", "Seafood", "Vegetarian"]
DEFAULT_UNITS = [
    "tsp", "tbsp", "fl oz", "cup", "pt", "qt", "gal", "oz", "lb",
    "each", "clove", "slice", "can", "package", "pinch", "dash",
    "small", "medium", "large",
]
# Single source of truth shared with the keyword guesser, so a guessed aisle
# and a hand-picked one always land in the same bucket.
DEFAULT_AISLES = grocery_categories.AISLE_ORDER


def _load_json(value):
    return None if value is None else json.loads(value)


def _slugify(name: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return slug or "recipe"


def _unique_recipe_path(recipes_dir: Path, base_name: str, taken: set) -> Path:
    slug = _slugify(base_name)
    candidate = recipes_dir / f"{slug}.yaml"
    counter = 2
    while candidate.exists() or candidate.name in taken:
        candidate = recipes_dir / f"{slug}-{counter}.yaml"
        counter += 1
    taken.add(candidate.name)
    return candidate


def _load_staging(staging_path: Path):
    if not staging_path.exists():
        return None
    try:
        return json.loads(staging_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return None


def _save_staging(staging_path: Path, data: dict) -> None:
    staging_path.write_text(json.dumps(data), encoding="utf-8")


def _delete_staging(staging_path: Path) -> None:
    staging_path.unlink(missing_ok=True)


def _discard_staged_images(staging, images_dir: Path) -> None:
    """Deletes the detail + thumbnail files for every photo a staging
    dict claims to own. Called both on an explicit cancel and right
    before a staging file is about to be overwritten by a new import,
    so a photo downloaded for a staged-but-not-yet-confirmed recipe
    never gets orphaned on disk with nothing left referencing it."""
    for image_filename in (staging or {}).get("downloaded_images", []):
        (images_dir / image_filename).unlink(missing_ok=True)
        (images_dir / _thumb_filename(image_filename)).unlink(missing_ok=True)


def _existing_recipe_names(conn) -> set:
    """Lower-cased names of every recipe already in the library. Import
    treats a name match as a duplicate: ignored by default, importable
    only if the user gives it a new name on the review screen."""
    return {row["name"].lower() for row in conn.execute("SELECT name FROM recipe").fetchall()}


def _display_value(value) -> str:
    if value is None or (isinstance(value, str) and value in recipe_sync.NONE_TOKENS):
        return ""
    return value


def _search_recipes(conn, query: str):
    """Recipes matching `query` across everything a cook might search by:
    the recipe's own name, the category and subcategory it files under, and
    the ingredient list, including the section headings inside it."""
    if not query:
        return conn.execute(
            "SELECT id, name, category, subcategory, image_filename, rating FROM recipe ORDER BY name"
        ).fetchall()

    like = f"%{query}%"
    return conn.execute(
        """SELECT DISTINCT r."id", r."name", r."category", r."subcategory",
                  r."image_filename", r."rating"
           FROM "recipe" r
           LEFT JOIN "recipe_ingredient" i ON i."recipe_id" = r."id"
           WHERE r."name" LIKE ?
              OR r."category" LIKE ?
              OR r."subcategory" LIKE ?
              OR i."name" LIKE ?
              OR i."section" LIKE ?
           ORDER BY r."name\"""",
        (like, like, like, like, like),
    ).fetchall()


def _group_recipes_by_category(rows) -> list:
    by_category: dict = {}
    for row in rows:
        category = row["category"] or "Uncategorized"
        subcategory = row["subcategory"] or ""
        by_category.setdefault(category, {}).setdefault(subcategory, []).append(row)

    ordered_categories = [c for c in DEFAULT_CATEGORIES if c in by_category]
    custom_categories = sorted(
        c for c in by_category if c not in DEFAULT_CATEGORIES and c != "Uncategorized"
    )
    ordered_categories += custom_categories
    if "Uncategorized" in by_category:
        ordered_categories.append("Uncategorized")

    groups = []
    for category in ordered_categories:
        subcats = by_category[category]
        subcategory_groups = [
            {"subcategory": name, "recipes": sorted(subcats[name], key=lambda r: r["name"])}
            for name in sorted(subcats)
            if name
        ]
        uncategorized_recipes = sorted(subcats.get("", []), key=lambda r: r["name"])
        groups.append({
            "category": category,
            "slug": _slugify(category),
            "subcategory_groups": subcategory_groups,
            "recipes": uncategorized_recipes,
        })
    return groups


def _group_by_aisle(rows) -> list:
    by_aisle: dict = {}
    for row in rows:
        aisle = row["aisle"] or "Uncategorized"
        by_aisle.setdefault(aisle, []).append(row)

    ordered_aisles = [a for a in DEFAULT_AISLES if a in by_aisle]
    custom_aisles = sorted(
        a for a in by_aisle if a not in DEFAULT_AISLES and a != "Uncategorized"
    )
    ordered_aisles += custom_aisles
    if "Uncategorized" in by_aisle:
        ordered_aisles.append("Uncategorized")

    return [
        {
            "aisle": aisle,
            "slug": _slugify(aisle),
            "items": sorted(by_aisle[aisle], key=lambda r: r["name"].lower()),
        }
        for aisle in ordered_aisles
    ]


def _ingredient_row_to_dict(row) -> dict:
    return {
        "name": row["name"],
        "usda_num": row["usda_num"],
        "amount": row["amount"],
        "unit": row["unit"],
        "section": row["section"],
        "processing": _load_json(row["processing_json"]),
        "notes": _load_json(row["ingredient_notes_json"]),
        "substitutions": _load_json(row["substitutions_json"]),
    }


def _step_row_to_dict(row) -> dict:
    return {
        "step_text": row["step_text"],
        "notes": _load_json(row["step_notes_json"]),
        "haccp": _load_json(row["haccp_json"]),
    }


def _scale_ingredients_for_display(ingredients: list, ratio) -> list:
    scaled = []
    for ing in ingredients:
        new_ing = dict(ing)
        new_ing["amount"] = unit_conversion.scale_amount_text(ing["amount"], ratio)
        if ing.get("substitutions"):
            new_ing["substitutions"] = [
                {
                    **sub,
                    "amounts": [
                        {
                            **a,
                            "amount": unit_conversion.scale_amount_text(
                                a.get("amount"), ratio
                            ),
                        }
                        for a in (sub.get("amounts") or [])
                    ],
                }
                for sub in ing["substitutions"]
            ]
        scaled.append(new_ing)
    return scaled


def _editor_rows(editable: list) -> list:
    """Flatten ingredients into the edit screen's single ordered list.

    A section is a row of its own, and owns every ingredient below it until
    the next section row, so dragging an ingredient under a heading is what
    puts it in that section. A heading with an empty name marks a return to
    no section, which is what keeps an arbitrary drag order round-trippable.
    """
    rows = []
    current_section = None
    for item in editable:
        if item["section"] != current_section:
            current_section = item["section"]
            rows.append(
                {
                    "kind": "section",
                    "key": f"s{len(rows)}",
                    "name": current_section or "",
                    "amount": "",
                    "unit": "",
                    "notes": "",
                    "locked": False,
                }
            )
        rows.append(
            {
                "kind": "ingredient",
                "key": f"e{item['index']}",
                "name": item["name"],
                "amount": item["amount"],
                "unit": item["unit"],
                "notes": "; ".join(item.get("notes") or []),
                "locked": item["multi_amount"],
                "needs_input": item.get("needs_input", False),
            }
        )
    return rows


def _ingredients_from_form(form, old_ingredients: list, prefix: str = "") -> list | None:
    """Rebuilds an ORF ingredients list from the ingredient editor's
    submitted rows. Returns None when the form carries no editor at all
    (no `<prefix>row_order` field) so the caller keeps what it had.

    The editor submits one ordered list of row keys; a row is either a
    sub-recipe heading or an ingredient, and each ingredient belongs to
    the heading above it. Order is taken as submitted so a drag sticks.
    Keys `e<n>` address `old_ingredients[n]`, whose usda_num/notes/etc.
    are carried forward; anything else is a brand-new row. A submitted
    `<prefix>row_notes_<key>` replaces the ingredient's notes (blank
    clears them); a form without that field leaves them as they were."""
    order_field = f"{prefix}row_order"
    if order_field not in form:
        return None
    order = [key for key in form.get(order_field, "").split(",") if key]
    new_ingredients = []
    section = None
    for key in order:
        name = form.get(f"{prefix}row_name_{key}", "").strip()
        if form.get(f"{prefix}row_kind_{key}") == "section":
            section = name or None
            continue
        if not name:
            continue

        original = None
        if key.startswith("e") and key[1:].isdigit():
            index = int(key[1:])
            if index < len(old_ingredients):
                original = old_ingredients[index]

        if original is not None:
            (original_name, body), = original.items()
            body = body or {}
            if len(body.get("amounts") or []) > 1:
                # Multiple amounts aren't editable here, so the ingredient
                # is carried through untouched apart from where it now sits.
                carried = dict(body)
                if section:
                    carried["section"] = section
                else:
                    carried.pop("section", None)
                new_ingredients.append({original_name: carried})
                continue
            preserved = dict(body)
            if name != original_name:
                preserved.pop("usda_num", None)
        else:
            preserved = {}

        notes_field = f"{prefix}row_notes_{key}"
        if notes_field in form:
            notes = recipe_sync.parse_notes_field(form.get(notes_field))
            if notes:
                preserved["notes"] = notes
            else:
                preserved.pop("notes", None)

        new_ingredients.append(
            recipe_sync.build_new_ingredient(
                name,
                form.get(f"{prefix}row_amount_{key}", ""),
                form.get(f"{prefix}row_unit_{key}", ""),
                section,
                preserved,
            )
        )
    return new_ingredients


def _steps_from_form(form, old_steps: list) -> list | None:
    """Rebuilds the ORF steps list from the step editor's submitted rows, or
    returns None when the form carries no step editor (no `step_order`).

    Same shape as the ingredient rows: `step_order` is the ordered list of
    row keys, `step_text_<key>` the text of each. Keys `e<n>` address
    `old_steps[n]`, whose notes/HACCP ride along with the step even after a
    reorder or a split (the first half of a split keeps the original key);
    any other key is a step typed in the editor. Blank rows are dropped."""
    if "step_order" not in form:
        return None
    new_steps = []
    for key in [k for k in form.get("step_order", "").split(",") if k]:
        text = form.get(f"step_text_{key}", "").strip()
        if not text:
            continue
        original = None
        if key.startswith("e") and key[1:].isdigit() and int(key[1:]) < len(old_steps):
            original = old_steps[int(key[1:])]
        step = dict(original) if isinstance(original, dict) else {}
        step["step"] = text
        new_steps.append(step)
    return new_steps


def _other_recipe_names(conn, recipe_id: int) -> set:
    """Like _existing_recipe_names, minus the recipe being edited, so a
    title can be re-saved unchanged (or with different capitalisation)."""
    return {
        row["name"].lower()
        for row in conn.execute("SELECT name FROM recipe WHERE id != ?", (recipe_id,)).fetchall()
    }


def _notes_lines(notes) -> str:
    """Recipe notes as one-per-line text for a textarea; accepts the list
    ORF specifies or a bare string from a hand-written file."""
    notes = recipe_sync._clean_none(notes)
    if notes is None:
        return ""
    if isinstance(notes, str):
        return notes
    return "\n".join(str(n) for n in notes)


def _first_oven_temp(data: dict) -> dict:
    temps = data.get("oven_temp")
    if isinstance(temps, list) and temps and isinstance(temps[0], dict):
        return {"amount": _display_value(temps[0].get("amount")), "unit": temps[0].get("unit") or "F"}
    return {"amount": "", "unit": "F"}


def _recipe_row_to_dict(row) -> dict:
    return {
        "id": row["id"],
        "name": row["name"],
        "author": row["author"],
        "source_authors": _load_json(row["source_authors_json"]),
        "source_url": row["source_url"],
        "source_book": _load_json(row["source_book_json"]),
        "oven_temp": _load_json(row["oven_temp_json"]),
        "oven_fan": row["oven_fan"],
        "oven_time": row["oven_time"],
        "yields": _load_json(row["yields_json"]),
        "notes": _load_json(row["notes_json"]),
        "category": row["category"],
        "subcategory": row["subcategory"],
        "image_filename": row["image_filename"],
        "rating": row["rating"],
    }


# Environment variables the app reads at startup. Both live in a `.env` under
# the user's AppData folder (see paths.py, `.env.example`, `set_password.py`);
# neither value is ever written into source or the install folder.
SECRET_KEY_ENV = "MEAL_PLANNER_SECRET_KEY"
PASSWORD_HASH_ENV = "MEAL_PLANNER_PASSWORD_HASH"
API_TOKEN_ENV = "MEAL_PLANNER_API_TOKEN"
HOST_ENV = "MEAL_PLANNER_HOST"
HA_URL_ENV = "MEAL_PLANNER_HA_URL"
HA_TOKEN_ENV = "MEAL_PLANNER_HA_TOKEN"
HA_TODO_ENTITY_ENV = "MEAL_PLANNER_HA_TODO_ENTITY"
BEHIND_PROXY_ENV = "MEAL_PLANNER_BEHIND_PROXY"
# Where the HTTPS proxy connects from. Tailscale Serve, and any reverse
# proxy on the same machine, comes in over loopback.
PROXY_IP_ENV = "MEAL_PLANNER_PROXY_IP"
DEFAULT_PROXY_IP = "127.0.0.1"

# Login: this many wrong passwords from one address in the window locks
# that address out until the window has passed. Defence in depth for the
# day the app is reachable from beyond the LAN (see the remote-access spec).
LOGIN_MAX_FAILURES = 5
LOGIN_WINDOW = timedelta(minutes=15)
PORT = 5000

# Routes that must stay reachable without a session: the login page itself
# and the static assets it renders with.
_PUBLIC_ENDPOINTS = frozenset({"login", "static", "healthz", "manifest"})

log = logging.getLogger(__name__)


class _SecureWhenHttps(SecureCookieSessionInterface):
    """Mark the session cookie Secure exactly when the request came over
    HTTPS. Flask's SESSION_COOKIE_SECURE is a static setting, but this
    app is reached both as plain http on the LAN and as https through a
    proxy (Tailscale Serve); a static True would make the LAN login
    impossible, a static False would send the HTTPS session in the clear."""

    def get_cookie_secure(self, app):
        return bool(request.is_secure)


class _LoginThrottle:
    """Per-address failed-login counter, in memory. One household on one
    box; reset on restart is fine."""

    def __init__(self, max_failures: int, window: timedelta):
        self.max_failures = max_failures
        self.window = window
        self._failures: dict = {}

    def _recent(self, address: str, now: datetime) -> list:
        times = [t for t in self._failures.get(address, []) if now - t < self.window]
        self._failures[address] = times
        return times

    def retry_after(self, address: str, now=None) -> timedelta | None:
        """How long until this address may try again, or None if it may now."""
        now = now or datetime.now()
        times = self._recent(address, now)
        if len(times) < self.max_failures:
            return None
        return self.window - (now - times[0])

    def record_failure(self, address: str, now=None) -> None:
        now = now or datetime.now()
        self._recent(address, now).append(now)

    def clear(self, address: str) -> None:
        self._failures.pop(address, None)


def create_app(
    recipes_dir: Path,
    db_path: Path,
    images_dir: Path | None = None,
    *,
    secret_key: str | None = None,
    password_hash: str | None = None,
    api_token: str | None = None,
    ha_link: "ha_sync.HALink | None" = None,
    start_ha_worker: bool | None = None,
    gcal_link: "gcal.GCalLink | None" = None,
    behind_proxy: bool | None = None,
) -> Flask:
    """`secret_key` and `password_hash` fall back to the MEAL_PLANNER_SECRET_KEY
    / MEAL_PLANNER_PASSWORD_HASH environment variables. With no secret key
    anywhere a random per-process one is used (sessions then reset on every
    restart); with no password hash anywhere the login gate is disabled --
    the `__main__` block refuses to start in that state, so this only ever
    applies to test clients. `api_token` falls back to MEAL_PLANNER_API_TOKEN;
    with none anywhere the `/api/voice/*` endpoints answer 503, so voice
    control is off until `set_api_token.py` is run. `ha_link` (else the
    MEAL_PLANNER_HA_URL / _HA_TOKEN / _HA_TODO_ENTITY variables) turns on
    the Home Assistant shopping-list mirror; `start_ha_worker` defaults to
    "whenever a link is configured" and tests pass False to keep the
    background thread out of the picture. `behind_proxy` (else
    MEAL_PLANNER_BEHIND_PROXY=1) makes the app trust one hop of
    X-Forwarded-* headers, for HTTPS via Tailscale Serve or another
    reverse proxy; off by default because on a bare LAN install any
    client could forge them."""
    app = Flask(__name__)
    if behind_proxy is None:
        behind_proxy = os.environ.get(BEHIND_PROXY_ENV, "").strip().lower() in ("1", "true", "yes", "on")
    app.config["BEHIND_PROXY"] = behind_proxy
    if behind_proxy:
        app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=1, x_port=1)
    app.session_interface = _SecureWhenHttps()
    app.extensions["login_throttle"] = _LoginThrottle(LOGIN_MAX_FAILURES, LOGIN_WINDOW)
    app.secret_key = secret_key or os.environ.get(SECRET_KEY_ENV) or secrets.token_hex(32)
    app.config["PASSWORD_HASH"] = password_hash or os.environ.get(PASSWORD_HASH_ENV) or None
    app.config["API_TOKEN"] = api_token or os.environ.get(API_TOKEN_ENV) or None
    if ha_link is None and os.environ.get(HA_URL_ENV) and os.environ.get(HA_TOKEN_ENV):
        ha_link = ha_sync.HALink(
            os.environ[HA_URL_ENV], os.environ[HA_TOKEN_ENV],
            os.environ.get(HA_TODO_ENTITY_ENV) or ha_sync.DEFAULT_ENTITY,
        )
    app.config["HA_LINK"] = ha_link
    if gcal_link is None:
        try:
            gcal_link = gcal.link_from_env(os.environ)
        except gcal.GCalError as exc:
            # A bad or missing key file must not stop the app booting; the
            # button simply reports it when pressed.
            app.logger.warning("Google calendar link unavailable: %s", exc)
            gcal_link = None
    app.config["GCAL_LINK"] = gcal_link
    app.extensions["ha_worker"] = None
    if ha_link is not None and (start_ha_worker if start_ha_worker is not None else True):
        worker = ha_sync.SyncWorker(Path(db_path), ha_link)
        worker.start()
        app.extensions["ha_worker"] = worker
    # The household shares one login; keep the phone signed in across kitchen
    # sessions rather than prompting every time the browser is closed.
    app.config["PERMANENT_SESSION_LIFETIME"] = timedelta(days=30)
    app.config["SESSION_COOKIE_HTTPONLY"] = True
    app.config["SESSION_COOKIE_SAMESITE"] = "Lax"
    app.config["RECIPE_IMAGES_DIR"] = (
        Path(images_dir) if images_dir is not None else Path(db_path).parent / paths.IMAGES_SUBDIR
    )
    app.config["RECIPES_DIR"] = Path(recipes_dir)
    app.config["DB_PATH"] = Path(db_path)
    app.config["STAGING_PATH"] = Path(db_path).parent / ".import_staging.json"
    app.config["CORRECTION_STAGING_PATH"] = Path(db_path).parent / ".correction_staging.json"

    def get_db():
        if "db" not in g:
            g.db = db.get_connection(app.config["DB_PATH"])
        return g.db

    @app.template_filter("friendly_date")
    def friendly_date(iso_text):
        """'2026-09-13' -> 'Sep 13, 2026'; anything unparseable comes back
        as-is so a stray value is visible rather than a 500."""
        try:
            d = date.fromisoformat(iso_text or "")
        except ValueError:
            return iso_text or ""
        return f"{d.strftime('%b')} {d.day}, {d.year}"

    @app.teardown_appcontext
    def close_db(exception=None):
        conn = g.pop("db", None)
        if conn is not None:
            conn.close()

    @app.after_request
    def security_headers(response):
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "same-origin")
        response.headers.setdefault("X-Frame-Options", "DENY")
        if request.is_secure:
            # Per-host, so the plain-http LAN name is unaffected.
            response.headers.setdefault("Strict-Transport-Security", "max-age=31536000")
        return response

    def _is_safe_next(target: str) -> bool:
        # Only ever bounce back to a path on this app -- never to another
        # host that a crafted login link could smuggle in via ?next=.
        return bool(target) and target.startswith("/") and not target.startswith("//")

    def _voice_reply(ok: bool, speech: str, status: int = 200):
        return {"ok": ok, "speech": speech}, status

    def _notify_ha() -> None:
        """The shopping list changed here; ask the mirror worker to push
        soon. Changes that arrived *from* Home Assistant (the webhook) must
        not call this, or every tick would echo once more than needed."""
        worker = app.extensions.get("ha_worker")
        if worker is not None:
            worker.request()

    def _ha_status() -> dict | None:
        if app.config["HA_LINK"] is None:
            return None
        worker = app.extensions.get("ha_worker")
        status = worker.status() if worker is not None else {}
        return {
            "entity": app.config["HA_LINK"].entity_id,
            "last_synced": status.get("last_synced"),
            "last_error": status.get("last_error"),
            "last_error_at": status.get("last_error_at"),
        }

    def _voice_token_ok() -> bool:
        header = request.headers.get("Authorization", "")
        scheme, _, presented = header.partition(" ")
        # The scheme match is deliberately case-sensitive: HA sends the literal secret.
        return scheme == "Bearer" and hmac.compare_digest(
            presented.strip().encode(), app.config["API_TOKEN"].encode()
        )

    def _voice_field(name: str) -> str:
        body = request.get_json(silent=True)
        if not isinstance(body, dict):
            body = {}
        value = body.get(name)
        if isinstance(value, str):
            return value
        # Home Assistant's Jinja templates parse a numeric-looking slot
        # ("2") into a native int/float before it ever reaches JSON, so a
        # plain quantity or date can arrive as a number, not a string.
        # bool is a subclass of int, so it is excluded explicitly.
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return str(value)
        return ""

    @app.before_request
    def require_login():
        if request.endpoint and request.endpoint.startswith(("api_voice_", "api_ha_")):
            # Home Assistant talks to these with a bearer token, not the
            # household password. The token opens nothing else.
            if app.config["API_TOKEN"] is None:
                return _voice_reply(False, voice.SPEECH_NOT_SET_UP, 503)
            if not _voice_token_ok():
                return _voice_reply(False, voice.SPEECH_REFUSED, 401)
            return None
        if app.config["PASSWORD_HASH"] is None:
            return None
        if request.endpoint in _PUBLIC_ENDPOINTS or session.get("authenticated"):
            return None
        if request.endpoint == "recipes_import_extension":
            # The Chrome extension talks JSON, not HTML -- give it something
            # it can show the user instead of a redirect to the login page.
            return {"ok": False, "error": "Sign in to the Meal Planner in this browser first."}, 401
        next_target = request.full_path.rstrip("?") if request.method == "GET" else url_for("index")
        return redirect(url_for("login", next=next_target))

    @app.route("/login", methods=["GET", "POST"])
    def login():
        if app.config["PASSWORD_HASH"] is None or session.get("authenticated"):
            return redirect(url_for("index"))
        error = None
        status = 200
        if request.method == "POST":
            throttle = app.extensions["login_throttle"]
            address = request.remote_addr or "?"
            wait = throttle.retry_after(address)
            if wait is not None:
                minutes = max(1, int(wait.total_seconds() // 60) + (1 if wait.total_seconds() % 60 else 0))
                error = f"Too many attempts. Try again in {minutes} minute{'s' if minutes != 1 else ''}."
                status = 429
            else:
                password = request.form.get("password", "")
                if password and check_password_hash(app.config["PASSWORD_HASH"], password):
                    throttle.clear(address)
                    session.clear()
                    session["authenticated"] = True
                    session.permanent = True
                    next_target = request.form.get("next", "")
                    return redirect(next_target if _is_safe_next(next_target) else url_for("index"))
                throttle.record_failure(address)
                log.warning("Failed login from %s", address)
                error = "That password isn't right."
                status = 401
        return render_template(
            "login.html", error=error, next=request.values.get("next", "")
        ), status

    @app.route("/logout", methods=["POST"])
    def logout():
        session.clear()
        return redirect(url_for("login"))

    @app.route("/")
    def index():
        return redirect(url_for("recipes_list"))

    @app.route("/healthz")
    def healthz():
        """Public, says nothing but "the app is up" -- to tell a sleeping PC
        from a VPN that is switched off, without reaching the login page."""
        return {"ok": True}

    @app.route("/manifest.webmanifest")
    def manifest():
        """Web-app manifest so a phone can pin the shopping list to its home
        screen as a standalone window. Public: browsers fetch it without
        cookies. Needs HTTPS to be honoured, which the remote path provides."""
        body = {
            "name": "Meal Planner",
            "short_name": "Meal Planner",
            "start_url": url_for("shopping_list_view"),
            "scope": "/",
            "display": "standalone",
            "background_color": "#ffffff",
            "theme_color": "#1c7a4d",
            "icons": [
                {"src": url_for("static", filename="icon-192.png"), "sizes": "192x192", "type": "image/png"},
                {"src": url_for("static", filename="icon-512.png"), "sizes": "512x512", "type": "image/png"},
            ],
        }
        response = app.response_class(json.dumps(body), mimetype="application/manifest+json")
        response.headers["Cache-Control"] = "public, max-age=86400"
        return response

    @app.route("/recipe-images/<path:filename>")
    def recipe_image(filename):
        # Photos live in the user's data folder, not under static/, so they
        # survive reinstalling or upgrading the app.
        return send_from_directory(app.config["RECIPE_IMAGES_DIR"], filename)

    @app.route("/recipes")
    def recipes_list():
        query = request.args.get("q", "").strip()
        conn = get_db()
        rows = _search_recipes(conn, query)
        groups = _group_recipes_by_category(rows)
        return render_template(
            "recipes_list.html",
            groups=groups,
            has_recipes=bool(rows),
            query=query,
            max_rating=recipe_sync.MAX_RATING,
        )

    @app.route("/recipes/<int:recipe_id>")
    def recipe_detail(recipe_id):
        conn = get_db()
        recipe_row = conn.execute(
            "SELECT * FROM recipe WHERE id = ?", (recipe_id,)
        ).fetchone()
        if recipe_row is None:
            abort(404)

        recipe = _recipe_row_to_dict(recipe_row)
        ingredient_rows = conn.execute(
            "SELECT * FROM recipe_ingredient WHERE recipe_id = ? ORDER BY order_num",
            (recipe_id,),
        ).fetchall()
        step_rows = conn.execute(
            "SELECT * FROM recipe_step WHERE recipe_id = ? ORDER BY order_num",
            (recipe_id,),
        ).fetchall()
        ingredients = [_ingredient_row_to_dict(r) for r in ingredient_rows]

        base_yield = recipe["yields"][0] if recipe["yields"] else None
        base_servings = unit_conversion.parse_amount(base_yield["amount"]) if base_yield else None
        requested_text = request.args.get("servings", "").strip()
        requested_servings = unit_conversion.parse_amount(requested_text) if requested_text else None

        display_servings = base_yield["amount"] if base_yield else None
        if base_servings and requested_servings and requested_servings > 0:
            ratio = requested_servings / base_servings
            ingredients = _scale_ingredients_for_display(ingredients, ratio)
            display_servings = unit_conversion.format_amount(requested_servings)

        # Flag what the pantry already covers, using the same matching the
        # shopping list will use, so the page agrees with the list.
        stocked = conn.execute(
            'SELECT "name", "exact_match" FROM "pantry_item" WHERE "active" = 1'
        ).fetchall()
        for ing in ingredients:
            ing["in_pantry"] = any(
                shopping_list.matches_pantry_item(row, ing["name"]) for row in stocked
            )

        return render_template(
            "recipe_detail.html",
            recipe=recipe,
            ingredients=ingredients,
            steps=[_step_row_to_dict(r) for r in step_rows],
            slots=meal_calendar.SLOTS,
            default_categories=DEFAULT_CATEGORIES,
            default_subcategories=DEFAULT_SUBCATEGORIES,
            base_yield=base_yield,
            display_servings=display_servings,
            max_rating=recipe_sync.MAX_RATING,
        )

    @app.route("/recipes/<int:recipe_id>/servings", methods=["POST"])
    def recipe_set_servings(recipe_id):
        conn = get_db()
        row = conn.execute(
            "SELECT file_path FROM recipe WHERE id = ?", (recipe_id,)
        ).fetchone()
        if row is None:
            abort(404)

        next_view = "recipe_edit_view" if request.form.get("next") == "edit" else "recipe_detail"

        amount = unit_conversion.parse_amount(request.form.get("amount", ""))
        if amount is None or amount <= 0:
            flash("Enter a valid serving amount.")
            return redirect(url_for(next_view, recipe_id=recipe_id))
        unit = request.form.get("unit", "").strip() or "servings"

        file_path = app.config["RECIPES_DIR"] / row["file_path"]
        data = yaml.safe_load(file_path.read_text(encoding="utf-8")) or {}
        data["yields"] = [
            {"amount": int(amount) if amount.denominator == 1 else unit_conversion.format_amount(amount), "unit": unit}
        ]
        yaml_text = yaml.safe_dump(data, sort_keys=False, allow_unicode=True)
        file_path.write_text(yaml_text, encoding="utf-8")

        recipe_sync.sync_recipes(app.config["RECIPES_DIR"], app.config["DB_PATH"])
        flash("Servings updated.")
        return redirect(url_for(next_view, recipe_id=recipe_id))

    @app.route("/recipes/<int:recipe_id>/rating", methods=["POST"])
    def recipe_set_rating(recipe_id):
        """Sets (1-5) or clears (0) a recipe's star rating, then returns to
        wherever the stars were clicked -- the list or the detail page."""
        conn = get_db()
        row = conn.execute(
            "SELECT file_path FROM recipe WHERE id = ?", (recipe_id,)
        ).fetchone()
        if row is None:
            abort(404)

        next_target = request.form.get("next", "")
        back = next_target if _is_safe_next(next_target) else url_for("recipe_detail", recipe_id=recipe_id)
        # star-rating.js submits with this header and repaints the stars in
        # place; a plain form post (no JS) still gets the redirect.
        wants_json = request.headers.get("X-Requested-With") == "fetch"

        rating_text = request.form.get("rating", "").strip()
        if not rating_text.isdigit() or not 0 <= int(rating_text) <= recipe_sync.MAX_RATING:
            message = f"A rating is 0 to {recipe_sync.MAX_RATING} stars."
            if wants_json:
                return {"ok": False, "error": message}, 400
            flash(message)
            return redirect(back)
        rating = int(rating_text)

        file_path = app.config["RECIPES_DIR"] / row["file_path"]
        raw_yaml = file_path.read_text(encoding="utf-8")
        raw_yaml = recipe_sync.patch_yaml_field(raw_yaml, "rating", rating if rating else "None")
        file_path.write_text(raw_yaml, encoding="utf-8")

        recipe_sync.sync_recipes(app.config["RECIPES_DIR"], app.config["DB_PATH"])
        if wants_json:
            return {"ok": True, "rating": rating, "max_rating": recipe_sync.MAX_RATING}
        return redirect(back)

    @app.route("/recipes/<int:recipe_id>/category", methods=["POST"])
    def recipe_set_category(recipe_id):
        conn = get_db()
        row = conn.execute(
            "SELECT file_path FROM recipe WHERE id = ?", (recipe_id,)
        ).fetchone()
        if row is None:
            abort(404)

        category = request.form.get("category", "").strip() or "None"
        subcategory = request.form.get("subcategory", "").strip() or "None"

        file_path = app.config["RECIPES_DIR"] / row["file_path"]
        raw_yaml = file_path.read_text(encoding="utf-8")
        raw_yaml = recipe_sync.patch_yaml_field(raw_yaml, "category", category)
        raw_yaml = recipe_sync.patch_yaml_field(raw_yaml, "subcategory", subcategory)
        file_path.write_text(raw_yaml, encoding="utf-8")

        recipe_sync.sync_recipes(app.config["RECIPES_DIR"], app.config["DB_PATH"])
        flash("Category updated.")
        return redirect(url_for("recipe_detail", recipe_id=recipe_id))

    def _recipe_images_dir() -> Path:
        images_dir = app.config["RECIPE_IMAGES_DIR"]
        images_dir.mkdir(parents=True, exist_ok=True)
        return images_dir

    @app.route("/recipes/<int:recipe_id>/image", methods=["POST"])
    def recipe_set_image(recipe_id):
        conn = get_db()
        row = conn.execute(
            "SELECT file_path, recipe_uuid FROM recipe WHERE id = ?", (recipe_id,)
        ).fetchone()
        if row is None:
            abort(404)

        uploaded = request.files.get("image")
        if uploaded is None or not uploaded.filename:
            flash("Choose a photo to upload.")
            return redirect(url_for("recipe_detail", recipe_id=recipe_id))

        try:
            image_filename = _process_and_save_recipe_image(
                uploaded.stream, _recipe_images_dir(), row["recipe_uuid"]
            )
        except (UnidentifiedImageError, OSError):
            flash("That file is not a valid image.")
            return redirect(url_for("recipe_detail", recipe_id=recipe_id))

        file_path = app.config["RECIPES_DIR"] / row["file_path"]
        raw_yaml = file_path.read_text(encoding="utf-8")
        raw_yaml = recipe_sync.patch_yaml_field(raw_yaml, "image", image_filename)
        file_path.write_text(raw_yaml, encoding="utf-8")

        recipe_sync.sync_recipes(app.config["RECIPES_DIR"], app.config["DB_PATH"])
        flash("Recipe photo updated.")
        return redirect(url_for("recipe_detail", recipe_id=recipe_id))

    @app.route("/recipes/<int:recipe_id>/image/delete", methods=["POST"])
    def recipe_remove_image(recipe_id):
        conn = get_db()
        row = conn.execute(
            "SELECT file_path, image_filename FROM recipe WHERE id = ?", (recipe_id,)
        ).fetchone()
        if row is None:
            abort(404)

        if row["image_filename"]:
            images_dir = _recipe_images_dir()
            (images_dir / row["image_filename"]).unlink(missing_ok=True)
            (images_dir / _thumb_filename(row["image_filename"])).unlink(missing_ok=True)

        file_path = app.config["RECIPES_DIR"] / row["file_path"]
        raw_yaml = file_path.read_text(encoding="utf-8")
        raw_yaml = recipe_sync.patch_yaml_field(raw_yaml, "image", "None")
        file_path.write_text(raw_yaml, encoding="utf-8")

        recipe_sync.sync_recipes(app.config["RECIPES_DIR"], app.config["DB_PATH"])
        flash("Recipe photo removed.")
        return redirect(url_for("recipe_detail", recipe_id=recipe_id))

    @app.route("/recipes/<int:recipe_id>/edit")
    def recipe_edit_view(recipe_id):
        conn = get_db()
        row = conn.execute(
            "SELECT file_path, name, image_filename, yields_json FROM recipe WHERE id = ?",
            (recipe_id,),
        ).fetchone()
        if row is None:
            abort(404)

        file_path = app.config["RECIPES_DIR"] / row["file_path"]
        data = yaml.safe_load(file_path.read_text(encoding="utf-8")) or {}
        editable = recipe_sync.build_editable_ingredients(data)

        yields = _load_json(row["yields_json"])
        base_yield = yields[0] if yields else None

        # Every step keeps its original index as its key so notes/HACCP
        # ride along with the step even after it is reordered or split.
        steps = [
            {"key": f"e{index}", "text": _display_value((step or {}).get("step"))}
            for index, step in enumerate(data.get("steps") or [])
        ]
        details = {
            "name": row["name"],
            "category": _display_value(data.get("category")),
            "subcategory": _display_value(data.get("subcategory")),
            "author": _display_value(data.get("author")),
            "source_url": _display_value(data.get("source_url")),
            "notes": _notes_lines(data.get("notes")),
            "oven_temp": _first_oven_temp(data),
            "oven_time": _display_value(data.get("oven_time")),
        }

        return render_template(
            "recipe_edit.html",
            recipe_id=recipe_id,
            recipe_name=row["name"],
            image_filename=row["image_filename"],
            rows=_editor_rows(editable),
            steps=steps,
            details=details,
            default_units=DEFAULT_UNITS,
            default_categories=DEFAULT_CATEGORIES,
            default_subcategories=DEFAULT_SUBCATEGORIES,
            base_yield=base_yield,
        )

    @app.route("/recipes/<int:recipe_id>/edit", methods=["POST"])
    def recipe_edit_confirm(recipe_id):
        conn = get_db()
        row = conn.execute(
            "SELECT file_path FROM recipe WHERE id = ?", (recipe_id,)
        ).fetchone()
        if row is None:
            abort(404)

        file_path = app.config["RECIPES_DIR"] / row["file_path"]
        data = yaml.safe_load(file_path.read_text(encoding="utf-8")) or {}
        edit_url = url_for("recipe_edit_view", recipe_id=recipe_id)

        # Each block below only applies when its fields were submitted, so a
        # form covering part of the recipe leaves the rest of the file alone.
        if "recipe_name" in request.form:
            new_name = request.form.get("recipe_name", "").strip()
            if not new_name:
                flash("A recipe needs a title.")
                return redirect(edit_url)
            if new_name.lower() in _other_recipe_names(conn, recipe_id):
                flash(f"Another recipe is already called '{new_name}'. Pick a different title.")
                return redirect(edit_url)
            data["recipe_name"] = new_name

        # Optional single-line details: a blank field clears the entry, the
        # same way a hand edit would delete the line from the YAML.
        for field_name in ("author", "source_url", "oven_time"):
            if field_name in request.form:
                value = request.form.get(field_name, "").strip()
                if value:
                    data[field_name] = value
                else:
                    data.pop(field_name, None)
        if "category" in request.form:
            data["category"] = request.form.get("category", "").strip() or "None"
        if "subcategory" in request.form:
            data["subcategory"] = request.form.get("subcategory", "").strip() or "None"
        if "notes" in request.form:
            notes = [line.strip() for line in request.form.get("notes", "").splitlines() if line.strip()]
            if notes:
                data["notes"] = notes
            else:
                data.pop("notes", None)
        if "oven_temp_amount" in request.form:
            temp_text = request.form.get("oven_temp_amount", "").strip()
            if temp_text:
                temp_unit = request.form.get("oven_temp_unit", "").strip().upper() or "F"
                data["oven_temp"] = [
                    {"amount": int(temp_text) if temp_text.isdigit() else temp_text, "unit": temp_unit}
                ]
            else:
                data.pop("oven_temp", None)
        if "servings_amount" in request.form:
            servings_text = request.form.get("servings_amount", "").strip()
            if servings_text:
                amount = unit_conversion.parse_amount(servings_text)
                if amount is None or amount <= 0:
                    flash("Enter a valid serving amount.")
                    return redirect(edit_url)
                data["yields"] = [{
                    "amount": int(amount) if amount.denominator == 1 else unit_conversion.format_amount(amount),
                    "unit": request.form.get("servings_unit", "").strip() or "servings",
                }]
            else:
                data.pop("yields", None)

        new_steps = _steps_from_form(request.form, data.get("steps") or [])
        if new_steps is not None:
            if not new_steps:
                flash("A recipe needs at least one instruction step.")
                return redirect(edit_url)
            data["steps"] = new_steps

        new_ingredients = _ingredients_from_form(request.form, data.get("ingredients") or [])
        if new_ingredients is not None:
            if not new_ingredients:
                flash("A recipe needs at least one ingredient.")
                return redirect(edit_url)
            data["ingredients"] = new_ingredients

        yaml_text = yaml.safe_dump(data, sort_keys=False, allow_unicode=True)
        file_path.write_text(yaml_text, encoding="utf-8")

        recipe_sync.sync_recipes(app.config["RECIPES_DIR"], app.config["DB_PATH"])
        flash("Recipe updated.")
        return redirect(url_for("recipe_detail", recipe_id=recipe_id))

    @app.route("/sync", methods=["POST"])
    def sync():
        result = recipe_sync.sync_recipes(app.config["RECIPES_DIR"], app.config["DB_PATH"])
        summary = f"Indexed {result.indexed}, unchanged {result.unchanged}, removed {result.removed}"
        if result.errors:
            summary += f", {len(result.errors)} error(s): "
            summary += "; ".join(f"{name}: {msg}" for name, msg in result.errors)
        if recipe_sync.find_duplicate_uuids(app.config["RECIPES_DIR"]):
            summary += f" — resolve conflicts: {url_for('sync_conflicts')}"
        flash(summary)

        corrections = []
        recipes_dir = app.config["RECIPES_DIR"]
        for file_name in result.indexed_files:
            path_obj = recipes_dir / file_name
            try:
                data = yaml.safe_load(path_obj.read_text(encoding="utf-8")) or {}
                issues = recipe_sync.find_unparseable_amount_slots(data)
            except Exception:
                continue
            for issue in issues:
                corrections.append({
                    "correction_id": len(corrections),
                    "file_name": path_obj.name,
                    "recipe_name": data.get("recipe_name", path_obj.name),
                    **issue,
                })

        if corrections:
            _save_staging(app.config["CORRECTION_STAGING_PATH"], {"entries": corrections})
            return redirect(url_for("recipes_corrections_review"))

        return redirect(url_for("recipes_list"))

    @app.route("/sync/conflicts")
    def sync_conflicts():
        conflicts = recipe_sync.find_duplicate_uuids(app.config["RECIPES_DIR"])
        return render_template("sync_conflicts.html", conflicts=conflicts)

    @app.route("/sync/conflicts/delete", methods=["POST"])
    def sync_conflicts_delete():
        file_name = request.form["file_name"]
        if Path(file_name).name != file_name:
            abort(400)
        (app.config["RECIPES_DIR"] / file_name).unlink(missing_ok=True)
        recipe_sync.sync_recipes(app.config["RECIPES_DIR"], app.config["DB_PATH"])
        flash(f"Deleted {file_name}")
        return redirect(url_for("sync_conflicts"))

    @app.route("/sync/conflicts/reassign", methods=["POST"])
    def sync_conflicts_reassign():
        file_name = request.form["file_name"]
        if Path(file_name).name != file_name:
            abort(400)
        recipe_sync.reassign_recipe_uuid(app.config["RECIPES_DIR"] / file_name)
        recipe_sync.sync_recipes(app.config["RECIPES_DIR"], app.config["DB_PATH"])
        flash(f"Assigned a new UUID to {file_name}")
        return redirect(url_for("sync_conflicts"))

    @app.route("/recipes/corrections/review")
    def recipes_corrections_review():
        staging = _load_staging(app.config["CORRECTION_STAGING_PATH"])
        if not staging or not staging["entries"]:
            flash("Nothing to review.")
            return redirect(url_for("recipes_list"))
        return render_template(
            "recipe_corrections_review.html",
            entries=staging["entries"],
            default_units=DEFAULT_UNITS,
        )

    @app.route("/recipes/corrections/confirm", methods=["POST"])
    def recipes_corrections_confirm():
        staging = _load_staging(app.config["CORRECTION_STAGING_PATH"])
        if not staging or not staging["entries"]:
            flash("Nothing to review.")
            return redirect(url_for("recipes_list"))

        recipes_dir = app.config["RECIPES_DIR"]
        by_file = {}
        for entry in staging["entries"]:
            by_file.setdefault(entry["file_name"], []).append(entry)

        for file_name, entries in by_file.items():
            file_path = recipes_dir / file_name
            data = yaml.safe_load(file_path.read_text(encoding="utf-8")) or {}
            for entry in entries:
                correction_id = entry["correction_id"]
                amount = request.form.get(f"amount_{correction_id}", entry["amount"])
                unit = request.form.get(f"unit_{correction_id}", entry["unit"])
                recipe_sync.apply_amount_correction(data, entry["slot_index"], amount, unit)
            file_path.write_text(
                yaml.safe_dump(data, sort_keys=False, allow_unicode=True), encoding="utf-8"
            )

        recipe_sync.sync_recipes(recipes_dir, app.config["DB_PATH"])
        flash(f"Corrected {len(staging['entries'])} ingredient amount(s).")
        _delete_staging(app.config["CORRECTION_STAGING_PATH"])
        return redirect(url_for("recipes_list"))

    @app.route("/recipes/corrections/skip", methods=["POST"])
    def recipes_corrections_skip():
        _delete_staging(app.config["CORRECTION_STAGING_PATH"])
        flash("Kept ingredient amounts as-is.")
        return redirect(url_for("recipes_list"))

    @app.route("/recipes/import", methods=["POST"])
    def recipes_import():
        uploaded = request.files.get("recipe_file")
        if uploaded is None or uploaded.filename == "":
            flash("Please choose a file to import.")
            return redirect(url_for("recipes_list"))

        recipes_dir = app.config["RECIPES_DIR"]
        safe_name = Path(uploaded.filename).name
        suffix = Path(safe_name).suffix.lower()

        if suffix in (".yaml", ".yml"):
            # Always write with a .yaml extension, regardless of what was
            # uploaded (.yml, .YAML, etc.) — sync_recipes only ever globs
            # *.yaml, so anything else would be written to disk and then
            # silently never indexed.
            dest_name = Path(safe_name).stem + ".yaml"
            raw_bytes = uploaded.read()

            # Validate before staging anything: ORF recipe files are UTF-8
            # text. If decoding or parsing fails, bail out without touching
            # recipes_dir or the staging file at all.
            try:
                text = raw_bytes.decode("utf-8")
                parsed = recipe_sync.parse_recipe_yaml(text)
            except Exception as exc:
                flash(f"{safe_name} could not be imported: {exc}")
                return redirect(url_for("recipes_list"))

            raw_data = yaml.safe_load(text) or {}
            amount_issues = recipe_sync.find_unparseable_amount_slots(raw_data)
            duplicate = parsed["name"].lower() in _existing_recipe_names(get_db())

            _discard_staged_images(
                _load_staging(app.config["STAGING_PATH"]), app.config["RECIPE_IMAGES_DIR"]
            )
            _save_staging(app.config["STAGING_PATH"], {
                "source_filename": safe_name,
                "entries": [
                    {
                        "temp_id": "0", "title": parsed["name"], "dest_filename": dest_name,
                        "yaml_text": text, "amount_issues": amount_issues,
                        "duplicate": duplicate,
                    }
                ],
                "skipped": [],
                "errors": [],
            })
            return redirect(url_for("recipes_import_review"))

        if suffix != ".mmf":
            flash(f"Unsupported file type: {safe_name}")
            return redirect(url_for("recipes_list"))

        # Meal Master files are CP1252, not UTF-8. A handful of byte values
        # (0x81, 0x8D, 0x8F, 0x90, 0x9D) are undefined in CP1252 and would
        # otherwise raise UnicodeDecodeError on a strict decode; fall back to
        # replacement characters for those rather than crashing the request.
        text = uploaded.read().decode("cp1252", errors="replace")
        result = meal_master.parse_meal_master(text)

        existing_names = _existing_recipe_names(get_db())

        # Every parsed recipe gets a review row. A title that is already in
        # the library -- or that appeared earlier in this same file -- is
        # flagged as a duplicate: ignored on confirm unless the user gives
        # it a new name. Filenames are only reserved for non-duplicates;
        # a renamed duplicate gets its filename from the new name at confirm.
        entries = []
        taken_filenames = set()
        for i, recipe in enumerate(result.recipes):
            duplicate = recipe["title"].lower() in existing_names
            dest_name = None
            if not duplicate:
                dest_name = _unique_recipe_path(recipes_dir, recipe["title"], taken_filenames).name
                existing_names.add(recipe["title"].lower())
            raw_data = yaml.safe_load(recipe["yaml_text"]) or {}
            entries.append({
                "temp_id": str(i),
                "title": recipe["title"],
                "dest_filename": dest_name,
                "yaml_text": recipe["yaml_text"],
                "amount_issues": recipe_sync.find_unparseable_amount_slots(raw_data),
                "duplicate": duplicate,
            })

        if not entries:
            summary = f"Imported 0 recipe(s) from {safe_name}"
            if result.errors:
                summary += f", {len(result.errors)} failed to parse: "
                summary += "; ".join(f"{cid}: {msg}" for cid, msg in result.errors)
            flash(summary)
            return redirect(url_for("recipes_list"))

        _discard_staged_images(
            _load_staging(app.config["STAGING_PATH"]), app.config["RECIPE_IMAGES_DIR"]
        )
        _save_staging(app.config["STAGING_PATH"], {
            "source_filename": safe_name,
            "entries": entries,
            "skipped": [],
            "errors": result.errors,
        })
        return redirect(url_for("recipes_import_review"))

    @app.route("/recipes/import/review")
    def recipes_import_review():
        staging = _load_staging(app.config["STAGING_PATH"])
        if not staging or not staging["entries"]:
            flash("Nothing to review.")
            return redirect(url_for("recipes_list"))

        rows = []
        duplicates = []
        needs_input_total = 0
        for entry in staging["entries"]:
            parsed = yaml.safe_load(entry["yaml_text"]) or {}
            base_yield = recipe_sync.first_yield(parsed)
            editor_rows = _editor_rows(recipe_sync.build_editable_ingredients(parsed))
            needs_input = sum(1 for r in editor_rows if r.get("needs_input"))
            needs_input_total += needs_input
            row = {
                "temp_id": entry["temp_id"],
                "title": entry["title"],
                "category": _display_value(parsed.get("category")),
                "subcategory": _display_value(parsed.get("subcategory")),
                "servings_amount": base_yield["amount"] if base_yield else "",
                "servings_unit": base_yield["unit"] if base_yield else "servings",
                "editor_rows": editor_rows,
                "needs_input": needs_input,
                "steps": [
                    str(step.get("step", "")) if isinstance(step, dict) else str(step)
                    for step in (parsed.get("steps") or [])
                ],
            }
            (duplicates if entry.get("duplicate") else rows).append(row)

        return render_template(
            "import_review.html",
            source_filename=staging["source_filename"],
            rows=rows,
            duplicates=duplicates,
            needs_input_total=needs_input_total,
            skipped=staging.get("skipped", []),
            errors=staging["errors"],
            default_categories=DEFAULT_CATEGORIES,
            default_subcategories=DEFAULT_SUBCATEGORIES,
            default_units=DEFAULT_UNITS,
        )

    @app.route("/recipes/import/confirm", methods=["POST"])
    def recipes_import_confirm():
        staging = _load_staging(app.config["STAGING_PATH"])
        if not staging or not staging["entries"]:
            flash("Nothing to review.")
            return redirect(url_for("recipes_list"))

        recipes_dir = app.config["RECIPES_DIR"]
        existing_names = _existing_recipe_names(get_db())
        # Titles this batch will create, so a rename can't collide with
        # another recipe in the same import.
        final_titles = set()
        taken_filenames = {e["dest_filename"] for e in staging["entries"] if e["dest_filename"]}
        to_write = []
        ignored = []
        for entry in staging["entries"]:
            temp_id = entry["temp_id"]
            submitted = request.form.get(f"title_{temp_id}", "").strip()
            if entry.get("duplicate"):
                # The title field is prefilled with the existing name; only
                # an actual change counts as "import a copy under this name".
                if not submitted or submitted.lower() == entry["title"].lower():
                    ignored.append(entry["title"])
                    continue
                title = submitted
            else:
                title = submitted or entry["title"]
            renamed = title.lower() != entry["title"].lower()
            if (renamed and title.lower() in existing_names) or title.lower() in final_titles:
                flash(
                    f"Can't import '{entry['title']}' as '{title}': that name is "
                    "already in your library. Pick another name"
                    + (", or leave the title as it was to ignore." if entry.get("duplicate") else ".")
                )
                return redirect(url_for("recipes_import_review"))
            final_titles.add(title.lower())
            dest_filename = entry["dest_filename"]
            if renamed:
                dest_filename = _unique_recipe_path(recipes_dir, title, taken_filenames).name
            to_write.append((entry, title if renamed else None, dest_filename))

        # Validate every editor before writing anything, so a bad recipe
        # halfway through the batch can't leave a partial import behind.
        prepared = []
        for entry, new_name, dest_filename in to_write:
            temp_id = entry["temp_id"]
            data = yaml.safe_load(entry["yaml_text"]) or {}
            new_ingredients = _ingredients_from_form(
                request.form, data.get("ingredients") or [], prefix=f"r{temp_id}_"
            )
            if new_ingredients is not None and not new_ingredients:
                flash(f"'{new_name or entry['title']}' needs at least one ingredient.")
                return redirect(url_for("recipes_import_review"))
            prepared.append((entry, new_name, dest_filename, data, new_ingredients))

        for entry, new_name, dest_filename, data, new_ingredients in prepared:
            temp_id = entry["temp_id"]
            category = request.form.get(f"category_{temp_id}", "").strip() or "None"
            subcategory = request.form.get(f"subcategory_{temp_id}", "").strip() or "None"

            servings_text = request.form.get(f"servings_amount_{temp_id}", "").strip()
            servings_amount = unit_conversion.parse_amount(servings_text) if servings_text else None
            set_yields = servings_amount is not None and servings_amount > 0
            servings_unit = request.form.get(f"servings_unit_{temp_id}", "").strip() or "servings"

            if new_ingredients is not None or set_yields:
                # A nested ingredient list (or the yields list) has no
                # single-line equivalent to patch_yaml_field, so this path
                # mutates the parsed dict and re-dumps the whole file --
                # unlike the line-patch below, this does not preserve the
                # original file's formatting.
                data["category"] = category
                data["subcategory"] = subcategory
                if new_name:
                    data["recipe_name"] = new_name
                    # A renamed copy is a new recipe, never an update of the
                    # one it collided with -- drop any uuid it carried so
                    # sync assigns a fresh identity instead of a conflict.
                    data["recipe_uuid"] = None
                if set_yields:
                    data["yields"] = [{
                        "amount": int(servings_amount) if servings_amount.denominator == 1
                        else unit_conversion.format_amount(servings_amount),
                        "unit": servings_unit,
                    }]
                if new_ingredients is not None:
                    data["ingredients"] = new_ingredients
                yaml_text = yaml.safe_dump(data, sort_keys=False, allow_unicode=True)
            else:
                yaml_text = recipe_sync.patch_yaml_field(entry["yaml_text"], "category", category)
                yaml_text = recipe_sync.patch_yaml_field(yaml_text, "subcategory", subcategory)
                if new_name:
                    yaml_text = recipe_sync.patch_yaml_field(yaml_text, "recipe_name", new_name)
                    yaml_text = recipe_sync.patch_yaml_field(yaml_text, "recipe_uuid", None)

            (recipes_dir / dest_filename).write_text(yaml_text, encoding="utf-8")

        sync_result = recipe_sync.sync_recipes(recipes_dir, app.config["DB_PATH"])

        summary = f"Imported {len(to_write)} recipe(s) from {staging['source_filename']}"
        if ignored:
            summary += f", ignored {len(ignored)} duplicate(s) already in your library: "
            summary += ", ".join(ignored)
        if staging["errors"]:
            summary += f", {len(staging['errors'])} failed to parse: "
            summary += "; ".join(f"{cid}: {msg}" for cid, msg in staging["errors"])
        if sync_result.errors:
            summary += f", {len(sync_result.errors)} sync error(s): "
            summary += "; ".join(f"{name}: {msg}" for name, msg in sync_result.errors)
        flash(summary)

        _delete_staging(app.config["STAGING_PATH"])
        return redirect(url_for("recipes_list"))

    @app.route("/recipes/import/cancel", methods=["POST"])
    def recipes_import_cancel():
        staging = _load_staging(app.config["STAGING_PATH"])
        _discard_staged_images(staging, app.config["RECIPE_IMAGES_DIR"])
        _delete_staging(app.config["STAGING_PATH"])
        flash("Import cancelled.")
        return redirect(url_for("recipes_list"))

    @app.route("/recipes/import/extension", methods=["POST"])
    def recipes_import_extension():
        payload = request.get_json(silent=True) or {}
        name = str(payload.get("name") or "").strip()
        ingredients = payload.get("ingredients") or []
        steps = payload.get("steps") or []
        if not name or not isinstance(ingredients, list) or not ingredients \
                or not isinstance(steps, list) or not steps:
            return {"ok": False, "error": "Missing name, ingredients, or steps."}, 400

        recipe_uuid = str(uuid.uuid4())
        data = recipe_extraction.build_recipe_data(payload, recipe_uuid)

        warning = None
        image_url = payload.get("image_url")
        downloaded_images = []
        if image_url:
            try:
                image_bytes = recipe_extraction.fetch_image_bytes(image_url)
                image_filename = _process_and_save_recipe_image(
                    io.BytesIO(image_bytes), _recipe_images_dir(), recipe_uuid
                )
                data["image"] = image_filename
                downloaded_images.append(image_filename)
            except Exception:
                # A bad/unreachable photo URL should never block the rest
                # of the import -- same philosophy as meal_master.py's
                # per-chunk error handling.
                warning = "Could not download photo"

        yaml_text = yaml.safe_dump(data, sort_keys=False, allow_unicode=True)
        dest = _unique_recipe_path(app.config["RECIPES_DIR"], name, set())
        amount_issues = recipe_sync.find_unparseable_amount_slots(data)
        source_display = recipe_extraction.domain_from_url(payload.get("source_url")) \
            or "browser extension"

        _discard_staged_images(
            _load_staging(app.config["STAGING_PATH"]), app.config["RECIPE_IMAGES_DIR"]
        )
        _save_staging(app.config["STAGING_PATH"], {
            "source_filename": source_display,
            "entries": [
                {
                    "temp_id": "0", "title": name, "dest_filename": dest.name,
                    "yaml_text": yaml_text, "amount_issues": amount_issues,
                    "duplicate": name.lower() in _existing_recipe_names(get_db()),
                }
            ],
            "skipped": [],
            "errors": [],
            "downloaded_images": downloaded_images,
        })

        response_body = {"ok": True}
        if warning:
            response_body["warning"] = warning
        return response_body

    @app.route("/calendar")
    def calendar_view():
        week_start = meal_calendar.parse_week_param(request.args.get("week"))
        dates = meal_calendar.get_week_dates(week_start)
        conn = get_db()
        plan = meal_calendar.get_week_plan(conn, week_start)

        days = []
        for d in dates:
            d_iso = d.isoformat()
            days.append({
                "date": d_iso,
                "weekday": d.strftime("%A"),
                "day_label": d.day,
                "date_label": f"{d.strftime('%b')} {d.day}",
                "slots": {slot: plan[(d_iso, slot)] for slot in meal_calendar.SLOTS},
            })

        today_iso = date.today().isoformat()
        date_isos = [day["date"] for day in days]
        focus_date = request.args.get("day", "")
        if focus_date not in date_isos:
            focus_date = today_iso if today_iso in date_isos else days[0]["date"]

        week_end = dates[-1]
        if week_start.month == week_end.month:
            week_label = f"{week_start.strftime('%b')} {week_start.day} – {week_end.day}"
        else:
            week_label = (
                f"{week_start.strftime('%b')} {week_start.day} – "
                f"{week_end.strftime('%b')} {week_end.day}"
            )

        return render_template(
            "calendar.html",
            days=days,
            slots=meal_calendar.SLOTS,
            week_start=week_start.isoformat(),
            week_label=week_label,
            prev_week=(week_start - timedelta(days=7)).isoformat(),
            next_week=(week_start + timedelta(days=7)).isoformat(),
            focus_date=focus_date,
            today=today_iso,
            google_calendar_linked=app.config.get("GCAL_LINK") is not None,
        )

    @app.route("/calendar/assign")
    def assign_meal_form():
        date_str = request.args.get("date", "")
        slot = request.args.get("slot", "")
        try:
            date.fromisoformat(date_str)
        except ValueError:
            abort(400)
        if slot not in meal_calendar.SLOTS:
            abort(400)

        query = request.args.get("q", "").strip()
        conn = get_db()
        rows = _search_recipes(conn, query)

        return render_template(
            "assign_meal.html", date=date_str, slot=slot, recipes=rows, query=query
        )

    @app.route("/calendar/assign", methods=["POST"])
    def assign_meal_submit():
        date_str = request.form["date"]
        slot = request.form["slot"]
        conn = get_db()
        try:
            recipe_id = int(request.form["recipe_id"])
            meal_calendar.assign_meal(
                conn, date_str, slot, recipe_id, request.form.get("servings")
            )
        except ValueError as exc:
            flash(str(exc))
            return redirect(url_for("assign_meal_form", date=date_str, slot=slot))

        week_start = meal_calendar.get_week_start(date.fromisoformat(date_str))
        return redirect(url_for("calendar_view", week=week_start.isoformat(), day=date_str))

    @app.route("/calendar/unassign", methods=["POST"])
    def unassign_meal_submit():
        date_str = request.form["date"]
        slot = request.form["slot"]
        conn = get_db()
        meal_calendar.unassign_meal(conn, date_str, slot)

        try:
            week_start = meal_calendar.get_week_start(date.fromisoformat(date_str))
            return redirect(url_for("calendar_view", week=week_start.isoformat(), day=date_str))
        except ValueError:
            return redirect(url_for("calendar_view"))

    @app.route("/calendar/google-push", methods=["POST"])
    def calendar_google_push():
        """Make the shared Google calendar match this week's plan.

        One way, on purpose: we only ever touch events this app created
        (their ids are in gcal_event). Anything else on the family calendar
        is none of our business, and nothing there is read back here.
        """
        week_start = meal_calendar.parse_week_param(request.form.get("week"))
        link = app.config.get("GCAL_LINK")
        if link is None:
            flash("No Google calendar is linked yet. Run `python set_gcal.py` on the machine "
                  "running the app, then restart it.")
            return redirect(url_for("calendar_view", week=week_start.isoformat()))

        try:
            result = gcal.push_week(
                get_db(), link, week_start, app_base_url=request.url_root
            )
        except gcal.GCalError as exc:
            flash(f"Could not update the Google calendar -- {exc}")
            return redirect(url_for("calendar_view", week=week_start.isoformat()))

        flash(result.summary())
        return redirect(url_for("calendar_view", week=week_start.isoformat()))

    @app.route("/pantry")
    def pantry_view():
        conn = get_db()
        items = pantry.list_items(conn)
        active_items = [item for item in items if item["active"]]
        removed_items = sorted(
            (item for item in items if not item["active"]),
            key=lambda r: r["name"].lower(),
        )
        return render_template(
            "pantry.html",
            groups=_group_by_aisle(active_items),
            removed_items=removed_items,
            has_items=bool(active_items),
            default_aisles=DEFAULT_AISLES,
        )

    @app.route("/pantry/add", methods=["POST"])
    def pantry_add():
        name = request.form.get("name", "")
        aisle = request.form.get("aisle", "")

        # Added from a recipe page: go back to the recipe the cook was reading,
        # at whatever serving size they had it scaled to.
        from_recipe = request.form.get("from_recipe", "")
        if from_recipe.isdigit():
            servings = request.form.get("from_servings", "").strip()
            back = url_for(
                "recipe_detail",
                recipe_id=int(from_recipe),
                **({"servings": servings} if servings else {}),
            )
        else:
            back = url_for("pantry_view")

        conn = get_db()
        try:
            added = pantry.add_item(conn, name, aisle)
        except ValueError:
            flash("Please enter an ingredient name.")
            return redirect(back)

        stripped = name.strip()
        if added:
            flash(f"Added '{stripped}' to your pantry.")
        else:
            existing = conn.execute(
                'SELECT "name" FROM "pantry_item" WHERE "name" = ? COLLATE NOCASE', (stripped,)
            ).fetchone()
            flash(f"'{existing['name']}' is already in your pantry.")
        return redirect(back)

    @app.route("/pantry/set-active", methods=["POST"])
    def pantry_set_active():
        item_id = int(request.form["item_id"])
        active = "active" in request.form
        conn = get_db()
        row = conn.execute(
            'SELECT "name" FROM "pantry_item" WHERE "id" = ?', (item_id,)
        ).fetchone()
        pantry.set_active(conn, item_id, active)
        if row is not None:
            if active:
                flash(f"Added '{row['name']}' back to your pantry.")
            else:
                flash(f"Removed '{row['name']}' from your pantry.")
        return redirect(url_for("pantry_view"))

    @app.route("/pantry/delete", methods=["POST"])
    def pantry_delete():
        item_id = int(request.form["item_id"])
        conn = get_db()
        row = conn.execute(
            'SELECT "name" FROM "pantry_item" WHERE "id" = ?', (item_id,)
        ).fetchone()
        pantry.delete_item(conn, item_id)
        if row is not None:
            flash(f"Deleted '{row['name']}' from your pantry.")
        return redirect(url_for("pantry_view"))

    @app.route("/pantry/aisle", methods=["POST"])
    def pantry_set_aisle():
        item_id = int(request.form["item_id"])
        aisle = request.form.get("aisle", "")
        conn = get_db()
        pantry.set_aisle(conn, item_id, aisle)
        return redirect(url_for("pantry_view"))

    @app.route("/pantry/added-on", methods=["POST"])
    def pantry_set_added_on():
        item_id = int(request.form["item_id"])
        conn = get_db()
        try:
            pantry.set_added_on(conn, item_id, request.form.get("added_on", ""))
        except ValueError:
            flash("Enter the date as YYYY-MM-DD, or leave it blank to clear it.")
        return redirect(url_for("pantry_view"))

    @app.route("/pantry/toggle-exact-match", methods=["POST"])
    def pantry_toggle_exact_match():
        item_id = int(request.form["item_id"])
        conn = get_db()
        pantry.toggle_exact_match(conn, item_id)
        return redirect(url_for("pantry_view"))

    @app.route("/shopping-list")
    def shopping_list_view():
        conn = get_db()
        items = shopping_list.list_items(conn)
        need_to_buy = [item for item in items if not item["in_pantry"]]
        already_have = [item for item in items if item["in_pantry"]]
        return render_template(
            "shopping_list.html",
            has_items=bool(items),
            need_to_buy_groups=_group_by_aisle(need_to_buy),
            already_have=already_have,
            default_aisles=DEFAULT_AISLES,
            default_units=DEFAULT_UNITS,
            ha=_ha_status(),
        )

    @app.route("/shopping-list/generate", methods=["POST"])
    def shopping_list_generate():
        week_start = date.fromisoformat(request.form["week"])
        conn = get_db()
        shopping_list.generate(conn, week_start)
        _notify_ha()
        return redirect(url_for("shopping_list_view"))

    @app.route("/shopping-list/add", methods=["POST"])
    def shopping_list_add():
        """Adds one named item to the list by hand -- from the shopping
        page's own add box, or from the pantry page where a staple that has
        run out is one click from the list. An amount is optional; with one,
        a same-name row already on the list absorbs it instead of a second
        row appearing."""
        name = request.form.get("name", "").strip()
        aisle = request.form.get("aisle", "")
        amount = request.form.get("amount", "").strip() or None
        unit = request.form.get("unit", "").strip() or None
        if amount is not None and unit_conversion.parse_amount(amount) is None:
            flash(f"Couldn't read the amount '{amount}' -- try a number like 2, 1/2 or 1.5.")
            return redirect(url_for("shopping_list_view"))
        if amount is None:
            unit = None
        next_target = request.form.get("next", "")
        back = next_target if _is_safe_next(next_target) else url_for("shopping_list_view")

        conn = get_db()
        try:
            status, _ = shopping_list.add_item(conn, name, aisle, amount=amount, unit=unit)
        except ValueError:
            flash("Please enter an item name.")
            return redirect(back)
        if status == "duplicate":
            flash(f"'{name}' is already on your shopping list.")
        elif status == "merged":
            flash(f"Added more '{name}' to the one already on your list.")
            _notify_ha()
        else:
            flash(f"Added '{name}' to your shopping list.")
            _notify_ha()
        return redirect(back)

    @app.route("/shopping-list/toggle", methods=["POST"])
    def shopping_list_toggle():
        item_id = int(request.form["item_id"])
        conn = get_db()
        shopping_list.toggle_checked(conn, item_id)
        _notify_ha()
        return redirect(url_for("shopping_list_view"))

    @app.route("/shopping-list/aisle", methods=["POST"])
    def shopping_list_set_aisle():
        item_id = int(request.form["item_id"])
        aisle = request.form.get("aisle", "")
        conn = get_db()
        shopping_list.set_aisle(conn, item_id, aisle)
        _notify_ha()
        return redirect(url_for("shopping_list_view"))

    @app.route("/shopping-list/remove", methods=["POST"])
    def shopping_list_remove():
        item_id = int(request.form["item_id"])
        conn = get_db()
        row = conn.execute(
            'SELECT "name" FROM "shopping_list_item" WHERE "id" = ?', (item_id,)
        ).fetchone()
        shopping_list.remove_item(conn, item_id)
        if row is not None:
            flash(f"Removed '{row['name']}' from your shopping list.")
        _notify_ha()
        return redirect(url_for("shopping_list_view"))

    @app.route("/shopping-list/clear", methods=["POST"])
    def shopping_list_clear():
        conn = get_db()
        shopping_list.clear(conn)
        flash("Cleared your shopping list.")
        _notify_ha()
        return redirect(url_for("shopping_list_view"))

    @app.route("/shopping-list/ha-sync", methods=["POST"])
    def shopping_list_ha_sync():
        """The button on the shopping page: reconcile with Home Assistant
        now, in this request, so the reloaded page shows the result."""
        link = app.config["HA_LINK"]
        if link is None:
            abort(404)
        worker = app.extensions.get("ha_worker")
        try:
            if worker is not None:
                worker.run_once()
                error = worker.status()["last_error"]
            else:
                ha_sync.push(get_db(), link)
                error = None
        except ha_sync.HAError as exc:
            error = str(exc)
        flash(f"Home Assistant sync failed: {error}" if error else "Synced with Home Assistant.")
        return redirect(url_for("shopping_list_view"))

    @app.route("/api/ha/shopping-list/sync", methods=["POST"])
    def api_ha_shopping_list_sync():
        """Home Assistant's automation posts the whole to-do list here
        after any change to it. HA wins for every item it knows; see
        ha_sync.apply_from_ha. Bearer-token guarded like /api/voice/*."""
        body = request.get_json(silent=True)
        items = body.get("items") if isinstance(body, dict) else None
        if not isinstance(items, list):
            return {"ok": False, "error": "Body must be {\"items\": [...]}"}, 400
        applied = ha_sync.apply_from_ha(get_db(), [i for i in items if isinstance(i, dict)])
        return {"ok": True, "applied": applied}

    # --- Voice (Alexa via Home Assistant) -----------------------------------
    # Each endpoint takes the raw slot strings Alexa heard and answers with
    # the sentence Alexa should say. Anything the user should hear about
    # (a blank item, no such recipe) is a 200 with ok=false: Home
    # Assistant's intent_script template only gets to look at the body
    # once the rest_command call has returned, and keeping user-facing
    # validation at 200 keeps HA's logs free of "non-2xx" warnings --
    # not because HA would otherwise drop the body, which it doesn't.

    @app.route("/api/voice/shopping-list", methods=["POST"])
    def api_voice_shopping_list():
        name = voice.item_name(_voice_field("item"))
        if name is None:
            return _voice_reply(False, voice.SPEECH_NOTHING_HEARD)
        amount = voice.normalize_quantity(_voice_field("quantity"))
        unit = voice.normalize_unit(_voice_field("unit"), amount)
        aisle = voice.normalize_aisle(_voice_field("aisle"))

        conn = get_db()
        status, item_id = shopping_list.add_item(conn, name, aisle, amount=amount, unit=unit)
        if status != "duplicate":
            _notify_ha()
        # By id, not by name: the same name can be on the list twice with
        # incompatible units, and the sentence must describe the row the
        # amount actually went into.
        row = conn.execute(
            'SELECT "amount", "unit", "aisle" FROM "shopping_list_item" WHERE "id" = ?',
            (item_id,),
        ).fetchone()
        return _voice_reply(True, voice.shopping_speech(status, name, row["amount"], row["unit"], row["aisle"]))

    @app.route("/api/voice/pantry", methods=["POST"])
    def api_voice_pantry():
        name = voice.item_name(_voice_field("item"))
        if name is None:
            return _voice_reply(False, voice.SPEECH_NOTHING_HEARD)
        aisle = voice.normalize_aisle(_voice_field("aisle"))

        conn = get_db()
        if pantry.add_item(conn, name, aisle):
            return _voice_reply(True, voice.pantry_speech("added", name))
        existing = conn.execute(
            'SELECT "id", "active" FROM "pantry_item" WHERE "name" = ? COLLATE NOCASE', (name,)
        ).fetchone()
        if existing["active"]:
            return _voice_reply(True, voice.pantry_speech("duplicate", name))
        pantry.set_active(conn, existing["id"], True)
        return _voice_reply(True, voice.pantry_speech("restored", name))

    @app.route("/api/voice/meal", methods=["POST"])
    def api_voice_meal():
        spoken = voice.given(_voice_field("recipe"))
        if spoken is None:
            return _voice_reply(False, voice.SPEECH_NOTHING_HEARD)
        slot = voice.normalize_meal(_voice_field("meal"))
        day = voice.parse_day(_voice_field("date"), date.today())
        if day is None:
            return _voice_reply(False, voice.SPEECH_NEED_A_DAY)

        conn = get_db()
        recipes = conn.execute('SELECT "id", "name" FROM "recipe" ORDER BY "name" COLLATE NOCASE').fetchall()
        match = voice.match_recipe(spoken, [row["name"] for row in recipes])
        if match.status == "none":
            return _voice_reply(False, voice.no_match_speech(spoken))
        if match.status == "ambiguous":
            return _voice_reply(False, voice.ambiguous_speech(match.candidates))
        recipe_id = next(row["id"] for row in recipes if row["name"] == match.name)

        previous = conn.execute(
            """SELECT r."name" FROM "meal_plan" mp JOIN "recipe" r ON r."id" = mp."recipe_id"
               WHERE mp."date" = ? AND mp."slot" = ?""",
            (day.isoformat(), slot),
        ).fetchone()
        previous_name = previous["name"] if previous else None
        if previous_name != match.name:
            meal_calendar.assign_meal(conn, day.isoformat(), slot, recipe_id)
        return _voice_reply(True, voice.meal_speech(match.name, slot, day, previous_name))

    return app


def serve_options(behind_proxy: bool, proxy_ip: str | None = None) -> dict:
    """Extra keyword arguments for waitress.serve().

    Waitress strips X-Forwarded-* headers from proxies it has not been told
    to trust (its default since 2.0), so without this ProxyFix never sees
    them: the app would treat an HTTPS request from the proxy as plain
    http, refuse to mark the session cookie Secure, and the login would
    bounce. Telling waitress the proxy's address makes it apply the
    headers itself and drop them, which ProxyFix then leaves alone."""
    if not behind_proxy:
        return {}
    return {
        "trusted_proxy": proxy_ip or os.environ.get(PROXY_IP_ENV) or DEFAULT_PROXY_IP,
        "trusted_proxy_count": 1,
        "trusted_proxy_headers": {"x-forwarded-for", "x-forwarded-proto", "x-forwarded-host"},
        "clear_untrusted_proxy_headers": True,
    }


def seed_data_dir(bundle_dir: Path, data_dir: Path) -> bool:
    """On first run, copies the starter recipes (and their photos) that ship
    with the app into the user's data folder. Returns True if it seeded.
    Never touches a data folder that already has a recipes/ directory, so
    upgrades can't clobber the user's library."""
    recipes_dest = data_dir / paths.RECIPES_SUBDIR
    if recipes_dest.exists():
        return False
    recipes_dest.mkdir(parents=True)
    for source in sorted((bundle_dir / "recipes").glob("*.yaml")):
        shutil.copy2(source, recipes_dest / source.name)
    bundled_images = bundle_dir / "static" / "recipe-images"
    if bundled_images.is_dir():
        images_dest = data_dir / paths.IMAGES_SUBDIR
        images_dest.mkdir(parents=True, exist_ok=True)
        for source in bundled_images.glob("*.jpg"):
            shutil.copy2(source, images_dest / source.name)
    return True


if __name__ == "__main__":
    from dotenv import load_dotenv
    from waitress import serve

    BASE_DIR = Path(__file__).parent
    load_dotenv(paths.env_path())
    missing = [name for name in (SECRET_KEY_ENV, PASSWORD_HASH_ENV) if not os.environ.get(name)]
    if missing:
        sys.exit(
            f"Refusing to start: {', '.join(missing)} not set in {paths.env_path()}. "
            "Run `python set_password.py` to create it (see .env.example)."
        )
    if seed_data_dir(BASE_DIR, paths.data_dir()):
        print(f"First run: created your recipe library at {paths.data_dir()}")
    flask_app = create_app(paths.recipes_dir(), paths.db_path(), paths.images_dir())
    recipe_sync.sync_recipes(flask_app.config["RECIPES_DIR"], flask_app.config["DB_PATH"])
    # Default to localhost-only; set MEAL_PLANNER_HOST=0.0.0.0 in .env to
    # reach the app from a phone on the home network. Waitress is a real
    # production WSGI server (multi-threaded, no debug console), unlike
    # Flask's built-in development server.
    host = os.environ.get(HOST_ENV, "127.0.0.1")
    print(f"Meal Planner serving on http://{host}:{PORT}  (data: {paths.data_dir()})", flush=True)
    link = flask_app.config["HA_LINK"]
    if link is not None:
        print(f"Home Assistant mirror: on -> {link.entity_id} at {link.base_url}", flush=True)
    else:
        print("Home Assistant mirror: off (run `python set_ha_link.py` to turn it on)", flush=True)
    options = serve_options(flask_app.config["BEHIND_PROXY"])
    if options:
        print(f"Trusting an HTTPS proxy at {options['trusted_proxy']}", flush=True)
    serve(flask_app, host=host, port=PORT, threads=8, **options)
