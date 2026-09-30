"""Links the Meal Planner to one of your own Google calendars, signing in
as you rather than as a service account.

Events then show you as their creator, and nothing has to be shared with a
robot address. The trade is that this app can see every calendar on the
account, where a service account only ever sees the one you shared with it
(see set_gcal.py, and DESIGN notes in docs/GOOGLE-CALENDAR.md).

You need an OAuth client first -- once, in your own Google account:

  1. https://console.cloud.google.com -> pick or create a project
  2. APIs & Services -> Library -> enable "Google Calendar API"
  3. APIs & Services -> OAuth consent screen -> External.

     WARNING: while the publishing status is "Testing", Google issues
     refresh tokens that expire after 7 days, so this link breaks weekly.
     Publishing the app fixes that, but Google will not let you publish
     without a homepage domain, a published privacy policy and terms of
     service. If you do not have those, use set_gcal.py instead -- a
     service account needs no consent screen at all.
  4. Credentials -> Create credentials -> OAuth client ID ->
     Application type **Desktop app**. Copy the client ID and secret.

Then run this. It opens your browser, you click Allow once, and it writes
the result to the same secrets file set_password.py manages.

    python set_gcal_oauth.py

Google stopped supporting paste-a-code in 2022, so the answer comes back
to a one-shot web server on 127.0.0.1. Nothing is exposed: the port is
picked at random, it serves exactly one request, and it is only ever
reachable from this machine.
"""
import base64
import getpass
import hashlib
import http.server
import json
import secrets
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
import webbrowser

import gcal
import paths
from set_password import read_env, write_env

AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
# calendar.events to write the meals; calendarlist.readonly only so this
# script can offer you a list to pick from instead of making you hunt for
# a calendar ID. Neither lets the app read the contents of other calendars.
SCOPES = "https://www.googleapis.com/auth/calendar.events " \
         "https://www.googleapis.com/auth/calendar.calendarlist.readonly"

PAGE = b"""<!doctype html><meta charset="utf-8">
<title>Meal Planner</title>
<body style="font-family: system-ui, sans-serif; margin: 4rem auto; max-width: 30rem; text-align: center">
<h1 style="color:#1c7a4d">Signed in</h1>
<p>You can close this tab and go back to the terminal.</p>
"""


class _CallbackHandler(http.server.BaseHTTPRequestHandler):
    """Catches Google's one redirect back, and nothing else."""

    result = {}

    def do_GET(self):  # noqa: N802 - name fixed by BaseHTTPRequestHandler
        query = urllib.parse.urlparse(self.path).query
        _CallbackHandler.result = dict(urllib.parse.parse_qsl(query))
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(PAGE)

    def log_message(self, *args):
        pass  # the http.server default scribbles over our own output


def _exchange(payload: dict) -> dict:
    body = urllib.parse.urlencode(payload).encode("ascii")
    request = urllib.request.Request(
        gcal.TOKEN_URL, data=body, method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as exc:
        detail = ""
        try:
            detail = ": " + (json.loads(exc.read() or b"{}").get("error_description") or "")
        except Exception:
            pass
        raise gcal.GCalError(f"Google rejected the sign-in ({exc.code}){detail.rstrip(': ')}") from exc
    except (urllib.error.URLError, OSError) as exc:
        raise gcal.GCalError(f"could not reach Google: {exc}") from exc


SIGN_IN_TIMEOUT = 300  # seconds to wait for the click on Allow


def sign_in(client_id: str, client_secret: str, timeout: float = SIGN_IN_TIMEOUT) -> str:
    """Run the loopback flow and return a refresh token."""
    # PKCE: proves the code came back to the same process that asked for it.
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode("ascii")).digest()
    ).rstrip(b"=").decode("ascii")
    state = secrets.token_urlsafe(16)

    _CallbackHandler.result = {}
    server = http.server.HTTPServer(("127.0.0.1", 0), _CallbackHandler)
    server.timeout = timeout  # handle_request gives up after this long
    port = server.server_address[1]
    redirect_uri = f"http://127.0.0.1:{port}/"
    thread = threading.Thread(target=server.handle_request, daemon=True)
    thread.start()

    url = AUTH_URL + "?" + urllib.parse.urlencode({
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": SCOPES,
        # offline + consent together are what guarantee a refresh token;
        # without them a second sign-in returns only an access token.
        "access_type": "offline",
        "prompt": "consent",
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "state": state,
    })
    print("Opening your browser to sign in to Google...")
    print("If nothing opens, paste this into a browser on this machine:")
    print(f"\n  {url}\n")
    webbrowser.open(url)
    print("Waiting for you to click Allow (Ctrl+C to give up)...")
    thread.join(timeout + 5)
    server.server_close()

    answer = _CallbackHandler.result
    if not answer:
        raise gcal.GCalError("timed out waiting for the Google sign-in")
    if answer.get("error"):
        raise gcal.GCalError(f"Google said: {answer['error']}")
    if answer.get("state") != state:
        raise gcal.GCalError("the reply did not match the request; try again")
    code = answer.get("code")
    if not code:
        raise gcal.GCalError("no authorisation code came back")

    tokens = _exchange({
        "client_id": client_id,
        "client_secret": client_secret,
        "code": code,
        "code_verifier": verifier,
        "grant_type": "authorization_code",
        "redirect_uri": redirect_uri,
    })
    refresh_token = tokens.get("refresh_token")
    if not refresh_token:
        raise gcal.GCalError(
            "Google returned no refresh token. Remove this app at "
            "https://myaccount.google.com/permissions and run this again."
        )
    return refresh_token


def list_calendars(credentials: "gcal.OAuthUser") -> list:
    request = urllib.request.Request(
        "https://www.googleapis.com/calendar/v3/users/me/calendarList",
        headers={"Authorization": f"Bearer {credentials.access_token()}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            payload = json.loads(response.read() or b"{}")
    except (urllib.error.URLError, OSError) as exc:
        raise gcal.GCalError(f"could not list your calendars: {exc}") from exc
    # Only the ones this app could actually write to.
    return [item for item in payload.get("items", [])
            if item.get("accessRole") in ("owner", "writer")]


def choose_calendar(calendars: list, ask=None) -> str:
    ask = ask or input  # looked up at call time so tests can patch input
    print("\nCalendars you can write to:")
    for number, item in enumerate(calendars, start=1):
        primary = "  (your main calendar)" if item.get("primary") else ""
        print(f"  {number}. {item.get('summary', item['id'])}{primary}")
    while True:
        answer = ask(f"\nWhich one? [1-{len(calendars)}]: ").strip()
        if answer.isdigit() and 1 <= int(answer) <= len(calendars):
            return calendars[int(answer) - 1]["id"]
        print("Enter one of the numbers above.")


def apply_oauth(values: dict[str, str], calendar_id: str, client_id: str,
                client_secret: str, refresh_token: str) -> None:
    values[gcal.CALENDAR_ID_ENV] = calendar_id
    values[gcal.CLIENT_ID_ENV] = client_id
    values[gcal.CLIENT_SECRET_ENV] = client_secret
    values[gcal.REFRESH_TOKEN_ENV] = refresh_token
    # Signing in as yourself replaces any service account that was set up
    # before; leaving the key behind would be a live credential nothing uses.
    values.pop(gcal.CREDENTIALS_ENV, None)


def main() -> int:
    env_path = paths.env_path()
    values = read_env(env_path)

    current_id = values.get(gcal.CLIENT_ID_ENV, "")
    prompt = f"OAuth client ID [{current_id[:20]}...]: " if current_id else "OAuth client ID: "
    client_id = input(prompt).strip() or current_id
    if not client_id:
        print("A client ID is required. See the notes at the top of this script.", file=sys.stderr)
        return 1
    client_secret = getpass.getpass(
        "OAuth client secret (leave blank to keep the saved one): "
    ).strip() or values.get(gcal.CLIENT_SECRET_ENV, "")
    if not client_secret:
        print("A client secret is required.", file=sys.stderr)
        return 1

    try:
        refresh_token = sign_in(client_id, client_secret)
    except gcal.GCalError as exc:
        print(f"\n{exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nGave up; nothing saved.", file=sys.stderr)
        return 1

    credentials = gcal.OAuthUser(client_id, client_secret, refresh_token)
    try:
        calendars = list_calendars(credentials)
    except gcal.GCalError as exc:
        print(f"\n{exc}", file=sys.stderr)
        return 1
    if not calendars:
        print("That account has no calendars you can write to.", file=sys.stderr)
        return 1
    calendar_id = choose_calendar(calendars)

    link = gcal.GCalLink(calendar_id, credentials)
    try:
        name = link.check()
    except gcal.GCalError as exc:
        print(f"\nCould not open that calendar: {exc}", file=sys.stderr)
        print("Nothing saved.", file=sys.stderr)
        return 1

    apply_oauth(values, calendar_id, client_id, client_secret, refresh_token)
    env_path.parent.mkdir(parents=True, exist_ok=True)
    write_env(env_path, values)

    print(f'\nLinked to "{name}" as you. Saved to {env_path}.')
    print("Restart the app; the meal plan page will have a "
          '"Send this week to Google Calendar" button.')
    return 0


if __name__ == "__main__":
    sys.exit(main())
