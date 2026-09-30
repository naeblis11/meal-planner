"""The installer's questions, one function per topic.

Each step asks through a wizard_io.Console and mutates `values` (the
secrets-file settings) and `ctx` (what the caller acts on after saving).
Nothing here writes to disk or opens a network connection of its own: the
network-facing pieces come in through the Context so tests can fake them.
"""
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import gcal
import ha_sync
import set_api_token
import set_gcal
import set_gcal_oauth
import set_ha_link
import set_password
import wizard_next

TARGET_WINDOWS, TARGET_PI, TARGET_LINUX = "windows", "pi", "linux"

HOST_ENV = "MEAL_PLANNER_HOST"
BEHIND_PROXY_ENV = "MEAL_PLANNER_BEHIND_PROXY"
ALL_INTERFACES = "0.0.0.0"


@dataclass
class Context:
    target: str
    make_ha_link: Callable = ha_sync.HALink
    load_service_account: Callable = gcal.ServiceAccount.from_file
    make_gcal_link: Callable = gcal.GCalLink
    oauth_sign_in: Callable = set_gcal_oauth.sign_in
    oauth_list_calendars: Callable = set_gcal_oauth.list_calendars
    # Results the caller acts on after saving:
    features: dict[str, bool] = field(default_factory=dict)  # "remote", "ha", "alexa", "gcal"
    tailscale_key: str = ""
    gcal_key_source: Path | None = None  # service-account key to store (local) or put on the card (pi)


def _is_server(ctx: Context) -> bool:
    return ctx.target in (TARGET_PI, TARGET_LINUX)


def ask_password(con, values, ctx) -> None:
    if values.get(set_password.PASSWORD_HASH_ENV):
        if con.yes_no("Keep the current household password?", True):
            return
    con.heading("Household password")
    con.say("Everyone in the household signs in with this one password.")
    while True:
        password = con.ask_secret("Household password: ")
        if not password:
            con.say("The password can't be empty.")
            continue
        if con.ask_secret("Type it again: ") != password:
            con.say("Those didn't match; try again.")
            continue
        break
    set_password.apply_password(values, password)


def ask_access(con, values, ctx) -> None:
    if ctx.target == TARGET_PI:
        con.say("The Pi is a server, so the app will be reachable from your home network.")
        values[HOST_ENV] = ALL_INTERFACES
        return
    con.heading("Who can open the app")
    home = _is_server(ctx) or values.get(HOST_ENV) == ALL_INTERFACES
    choice = con.choose(
        "Who should be able to open the app?",
        ["Only this computer", "Phones and computers on your home network"],
        default=2 if home else 1,
    )
    if choice == 2:
        values[HOST_ENV] = ALL_INTERFACES
    else:
        values.pop(HOST_ENV, None)


def ask_remote(con, values, ctx) -> None:
    con.heading("Remote access (for the grocery store)")
    con.say("Tailscale is an encrypted private network between your devices.")
    con.say("Nothing is opened on your router, so nothing is exposed to the internet.")
    con.say("The personal plan is free.")
    on = con.yes_no("Turn on remote access with Tailscale?",
                    default=BEHIND_PROXY_ENV in values)
    if not on:
        values.pop(BEHIND_PROXY_ENV, None)
        ctx.features["remote"] = False
        return
    values[BEHIND_PROXY_ENV] = "1"
    ctx.features["remote"] = True
    con.say()
    con.say("1. Install Tailscale on each phone: https://tailscale.com/download")
    con.say("2. In the admin console turn on MagicDNS and HTTPS certificates: "
            "https://login.tailscale.com/admin/dns")
    if _is_server(ctx):
        con.say("Make an auth key at https://login.tailscale.com/admin/settings/keys "
                "(Generate auth key; leave Reusable and Ephemeral off).")
        ctx.tailscale_key = con.ask(
            "Auth key (blank if this machine is already signed in to Tailscale)")
        if ctx.target == TARGET_PI and not ctx.tailscale_key:
            for line in wizard_next.PI_TAILSCALE_STEPS:
                con.say(line)
    else:
        con.say("Install Tailscale on this PC too and sign in, then run this once in a terminal:")
        con.say("  tailscale serve --bg --https=443 http://127.0.0.1:5000")


def _ha_off(values, ctx) -> None:
    set_ha_link.clear_ha_link(values)
    ctx.features["ha"] = False


def ask_home_assistant(con, values, ctx) -> None:
    con.heading("Home Assistant")
    con.say("This mirrors the shopping list into a Home Assistant to-do list, so it also "
            "shows up on your Home Assistant dashboards and phone app.")
    on = con.yes_no("Use the shopping list from Home Assistant?",
                    default=bool(values.get(set_ha_link.HA_URL_ENV)))
    if not on:
        _ha_off(values, ctx)
        return
    while True:
        url = con.ask("Home Assistant address",
                      values.get(set_ha_link.HA_URL_ENV) or "http://homeassistant.local:8123")
        con.say("In Home Assistant: click your name (bottom left) -> Security -> "
                "Long-lived access tokens -> Create token. Copy it now; HA shows it once.")
        token = (con.ask_secret("Long-lived access token (blank = keep the saved one): ")
                 or values.get(set_ha_link.HA_TOKEN_ENV, ""))
        if not token:
            con.say("A token is required.")
            continue
        con.say("The to-do list the app keeps in step. To make one: Settings -> Devices & "
                "services -> Add integration -> Local To-do, name it Shopping List.")
        entity = con.ask("To-do list entity",
                         values.get(set_ha_link.HA_TODO_ENTITY_ENV) or ha_sync.DEFAULT_ENTITY)
        try:
            count = set_ha_link.check_ha_link(url, token, entity, make_link=ctx.make_ha_link)
        except ha_sync.HAError as exc:
            con.say(f"Couldn't reach that list: {exc}")
            choice = con.choose("What now?", ["Try again",
                                              "Save it anyway (I'll check it later)",
                                              "Skip Home Assistant"])
            if choice == 1:
                continue
            if choice == 3:
                _ha_off(values, ctx)
                return
            # 2: save unchecked; normal on the Pi when HA isn't reachable from this PC.
        else:
            con.say(f"Connected: {entity} has {count} item(s).")
        set_ha_link.apply_ha_link(values, url, token, entity)
        set_api_token.ensure_api_token(values)
        ctx.features["ha"] = True
        break
    if ctx.target == TARGET_WINDOWS and HOST_ENV not in values:
        con.say("Home Assistant has to reach this PC over the network, so the app will "
                "listen on your home network.")
        values[HOST_ENV] = ALL_INTERFACES


def ask_alexa(con, values, ctx) -> None:
    con.heading("Alexa")
    con.say("The Alexa voice skill needs Home Assistant (with Nabu Casa or another way for "
            "Alexa to reach it) and an Alexa developer account. Setup is in alexa/SETUP.md.")
    on = con.yes_no("Will you use the Alexa voice skill?",
                    default=set_api_token.API_TOKEN_ENV in values)
    ctx.features["alexa"] = on
    if on:
        set_api_token.ensure_api_token(values)
    elif not ctx.features.get("ha"):
        values.pop(set_api_token.API_TOKEN_ENV, None)


SERVICE_ACCOUNT_STEPS = (
    "1. Go to https://console.cloud.google.com and make a new project.",
    "2. APIs & Services -> enable \"Google Calendar API\".",
    "3. Credentials -> Create credentials -> Service account.",
    "4. Open the service account -> Keys -> Add key -> JSON. Save the file somewhere you can find it.",
    "5. In Google Calendar, open the shared calendar -> Settings and sharing -> "
    "\"Share with specific people or groups\" -> add the service account's email "
    "(name@project.iam.gserviceaccount.com) with permission \"Make changes to events\".",
    "6. The Calendar ID is further down that same page, under \"Integrate calendar\".",
)

OAUTH_STEPS = (
    "1. Go to https://console.cloud.google.com and pick or create a project.",
    "2. APIs & Services -> Library -> enable \"Google Calendar API\".",
    "3. APIs & Services -> OAuth consent screen -> External.",
    "   WARNING: while the publishing status is \"Testing\", Google makes the sign-in expire "
    "after 7 days, so this link breaks weekly. Publishing needs a homepage domain, a privacy "
    "policy and terms of service; if you don't have those, use a service account instead.",
    "4. Credentials -> Create credentials -> OAuth client ID -> Application type \"Desktop app\". "
    "Copy the client ID and secret.",
)


def _gcal_failed(con, exc, hint="") -> bool:
    """Show the error; True to try again, False to skip Google Calendar."""
    con.say(f"That didn't work: {exc}")
    if hint:
        con.say(hint)
    return con.choose("What now?", ["Try again", "Skip Google Calendar"]) == 1


def _gcal_service_account(con, values, ctx) -> bool:
    for line in SERVICE_ACCOUNT_STEPS:
        con.say(line)
    while True:
        typed = con.ask("Path to the downloaded JSON key (blank = skip Google Calendar)").strip('"')
        if not typed:
            return False
        path = set_gcal.resolve_key_input(typed, "")
        if path is None or not path.is_file():
            con.say(f"No file at {path or typed!r}. Check the path and try again.")
            continue
        calendar_id = con.ask("Calendar ID")
        loaded = []

        def load(key_path):
            account = ctx.load_service_account(key_path)
            loaded.append(account)
            return account

        try:
            set_gcal.check_service_account(path, calendar_id, load=load,
                                           make_link=ctx.make_gcal_link)
        except gcal.GCalError as exc:
            email = loaded[0].client_email if loaded else "the service account"
            if _gcal_failed(con, exc, f"Usually: the calendar isn't shared with {email} yet."):
                continue
            return False
        values[gcal.CALENDAR_ID_ENV] = calendar_id
        for key in (gcal.CLIENT_ID_ENV, gcal.CLIENT_SECRET_ENV, gcal.REFRESH_TOKEN_ENV):
            values.pop(key, None)
        ctx.gcal_key_source = path
        return True


def _gcal_own_account(con, values, ctx) -> bool:
    for line in OAUTH_STEPS:
        con.say(line)
    while True:
        client_id = con.ask("OAuth client ID")
        secret = con.ask_secret("OAuth client secret: ")
        try:
            refresh = ctx.oauth_sign_in(client_id, secret)
            calendars = ctx.oauth_list_calendars(gcal.OAuthUser(client_id, secret, refresh))
            if not calendars:
                raise gcal.GCalError("that account has no calendars you can write to")
            calendar_id = set_gcal_oauth.choose_calendar(
                calendars, ask=lambda p: con.ask(p.strip().rstrip(":").strip()))
        except gcal.GCalError as exc:
            if _gcal_failed(con, exc):
                continue
            return False
        set_gcal_oauth.apply_oauth(values, calendar_id, client_id, secret, refresh)
        return True


def _restore_gcal(values, snapshot) -> None:
    set_gcal.clear_gcal(values)
    values.update(snapshot)


def ask_google_calendar(con, values, ctx) -> None:
    con.heading("Google Calendar")
    con.say("This pushes the week's meals onto a shared Google calendar. It only writes "
            "events; it never reads the calendar back.")
    if values.get(gcal.CALENDAR_ID_ENV):
        if con.yes_no("Keep the current Google Calendar link?", True):
            ctx.features["gcal"] = True
            return
    on = con.yes_no("Put meals on a Google calendar?",
                    default=bool(values.get(gcal.CALENDAR_ID_ENV)))
    if not on:
        set_gcal.clear_gcal(values)
        ctx.features["gcal"] = False
        return
    # A failed attempt must not cost a link that was working before it.
    snapshot = {key: values[key] for key in set_gcal.GCAL_KEYS if key in values}
    choice = con.choose("How should it sign in?", [
        "A service account (recommended: never expires, sees only the calendar you share)",
        "Your own Google account",
    ])
    if choice == 1:
        done = _gcal_service_account(con, values, ctx)
    else:
        done = _gcal_own_account(con, values, ctx)
    if not done:
        _restore_gcal(values, snapshot)
        ctx.gcal_key_source = None
    ctx.features["gcal"] = bool(values.get(gcal.CALENDAR_ID_ENV))


STEPS = (ask_password, ask_access, ask_remote, ask_home_assistant, ask_alexa, ask_google_calendar)
