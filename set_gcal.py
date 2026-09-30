"""Links the Meal Planner to a shared Google calendar, so a week's meals
can be pushed onto it with one button.

One way only: the app writes meal events and never reads the calendar
back, so the family can edit, move and add whatever they like around them.

You need two things first, both done once in your own Google account:

  1. A service account and its JSON key.
     https://console.cloud.google.com -> new project -> APIs & Services ->
     enable "Google Calendar API" -> Credentials -> Create credentials ->
     Service account -> then Keys -> Add key -> JSON. Save that file
     somewhere the app can read it.
  2. Share the calendar with it.
     Google Calendar -> the shared calendar -> Settings and sharing ->
     "Share with specific people or groups" -> add the service account's
     email (it looks like name@project.iam.gserviceaccount.com) with
     permission "Make changes to events".

Then run this. It proves the link by reading the calendar's name before it
saves anything, and writes MEAL_PLANNER_GCAL_ID / _GCAL_CREDENTIALS into
the same secrets file set_password.py manages. Restart the app afterwards.

    python set_gcal.py
"""
import json
import shutil
import sys
from pathlib import Path

from dotenv import load_dotenv

import gcal
import paths
from set_password import read_env, write_env

GCAL_KEYS = (gcal.CALENDAR_ID_ENV, gcal.CREDENTIALS_ENV, gcal.CLIENT_ID_ENV,
             gcal.CLIENT_SECRET_ENV, gcal.REFRESH_TOKEN_ENV)


def check_service_account(key_path: Path, calendar_id: str,
                          load=gcal.ServiceAccount.from_file,
                          make_link=gcal.GCalLink) -> tuple[str, str]:
    """Returns (client_email, calendar name). Raises gcal.GCalError."""
    account = load(key_path)
    name = make_link(calendar_id, account).check()
    return account.client_email, name


def store_key(key_path: Path) -> Path:
    """Copy the key into the app's data folder; returns where it lives. Raises OSError."""
    stored = gcal.stored_key_path()
    if key_path.resolve() != stored.resolve():
        stored.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(key_path, stored)
    return stored


def apply_service_account(values: dict[str, str], calendar_id: str, stored: Path) -> None:
    values[gcal.CALENDAR_ID_ENV] = calendar_id
    values[gcal.CREDENTIALS_ENV] = str(stored)
    # gcal.credentials_from_env prefers OAuth, so a leftover refresh token
    # would silently win over the service account just saved.
    for key in (gcal.CLIENT_ID_ENV, gcal.CLIENT_SECRET_ENV, gcal.REFRESH_TOKEN_ENV):
        values.pop(key, None)


def clear_gcal(values: dict[str, str]) -> None:
    for key in GCAL_KEYS:
        values.pop(key, None)


def resolve_key_input(typed: str, saved: str):
    """What the user typed, or the saved setting, as a path.

    `~` is expanded because that is how anyone describes a file in their
    home directory over SSH, and Path() alone would look for a directory
    literally named "~".
    """
    text = typed or saved
    if not text:
        return None
    return Path(text).expanduser()


def main() -> int:
    # app.py reads the secrets file on startup, so settings kept there --
    # MEAL_PLANNER_DATA_DIR in particular -- only reach the environment once
    # it has. Without this the app and this script disagree about where the
    # data folder is, and the key is filed where the app never looks.
    #
    # Inside main(), never at import: this mutates os.environ for the whole
    # process, and importing this module from a test would otherwise drag
    # the developer's real password hash and Home Assistant token into every
    # other test in the run.
    load_dotenv(paths.env_path())
    env_path = paths.env_path()
    values = read_env(env_path)

    current_key = values.get(gcal.CREDENTIALS_ENV, "")
    prompt = f"Service account JSON key file [{current_key}]: " if current_key \
        else "Service account JSON key file: "
    key_input = input(prompt).strip().strip('"')
    key_path = resolve_key_input(key_input, current_key)
    if key_path is None:
        print("A key file is required. See the notes at the top of this script.", file=sys.stderr)
        return 1
    if not key_path.is_file():
        print(f"No file at {key_path}.", file=sys.stderr)
        if not key_path.is_absolute():
            print("That was read relative to the folder this script runs in,", file=sys.stderr)
            print(f"which is {Path.cwd()}.", file=sys.stderr)
            print("Give the full path instead if the key is somewhere else.", file=sys.stderr)
        return 1

    # Keep the key with the app's own data rather than wherever it was
    # downloaded to -- the Downloads folder is not somewhere a credential
    # should live for years, and the data folder is already the private one.
    stored = gcal.stored_key_path()
    if key_path.resolve() != stored.resolve():
        try:
            stored = store_key(key_path)
        except OSError as exc:
            print(f"\nCould not put the key in the app's data folder:\n  {stored.parent}\n"
                  f"  {exc}\n", file=sys.stderr)
            print("That is the folder the app keeps recipes and the database in.", file=sys.stderr)
            print("If it is wrong, set MEAL_PLANNER_DATA_DIR to the right one in",
                  file=sys.stderr)
            print(f"  {paths.env_path()}", file=sys.stderr)
            print("and run this again. Check where the app itself is looking with:",
                  file=sys.stderr)
            print("  python -c \"import paths; print(paths.data_dir())\"", file=sys.stderr)
            return 1
        print(f"Copied the key to {stored}")
        print("You can delete the downloaded copy now.")

    try:
        account = gcal.ServiceAccount.from_file(stored)
    except gcal.GCalError as exc:
        print(f"{exc}", file=sys.stderr)
        return 1
    print(f"Service account: {account.client_email}")

    current_id = values.get(gcal.CALENDAR_ID_ENV, "")
    prompt = f"Calendar ID [{current_id}]: " if current_id else \
        "Calendar ID (Google Calendar -> Settings and sharing -> Integrate calendar): "
    calendar_id = input(prompt).strip() or current_id
    if not calendar_id:
        print("A calendar ID is required.", file=sys.stderr)
        return 1

    try:
        _, name = check_service_account(stored, calendar_id)
    except gcal.GCalError as exc:
        print(f"Could not open that calendar: {exc}", file=sys.stderr)
        print("Nothing saved. The usual cause is that the calendar has not been shared with",
              file=sys.stderr)
        print(f"  {account.client_email}", file=sys.stderr)
        print('with permission "Make changes to events".', file=sys.stderr)
        return 1

    apply_service_account(values, calendar_id, stored)
    env_path.parent.mkdir(parents=True, exist_ok=True)
    write_env(env_path, values)
    print(f'Linked to "{name}". Saved to {env_path}.')
    print("Restart the app; the meal plan page will have a "
          '"Send this week to Google Calendar" button.')
    return 0


if __name__ == "__main__":
    sys.exit(main())
