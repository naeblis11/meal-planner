"""Sets (or resets) the household password and secret key.

Prompts for the password without echoing it, stores only a salted scrypt
hash (never the password itself), and generates a random secret key on
first run. Run it again any time to change the password; the secret key
is preserved so existing sign-ins stay valid. The file lives under the
user's AppData folder (see paths.py), never in the install directory.

    python set_password.py
"""
import getpass
import os
import secrets
import sys
from pathlib import Path

from werkzeug.security import generate_password_hash

import paths

SECRET_KEY_ENV = "MEAL_PLANNER_SECRET_KEY"
PASSWORD_HASH_ENV = "MEAL_PLANNER_PASSWORD_HASH"
# Unattended installs (the Raspberry Pi first-boot provisioner) pass the
# password this way instead of typing it at the prompt.
SETUP_PASSWORD_ENV = "MEAL_PLANNER_SETUP_PASSWORD"


def read_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values


def write_env(path: Path, values: dict[str, str]) -> None:
    lines = ["# Meal Planner secrets. Regenerate with `python set_password.py`."]
    lines += [f"{key}={value}" for key, value in values.items()]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def apply_password(values: dict[str, str], password: str) -> None:
    """Store the password's hash, and a session key if there isn't one yet."""
    values[PASSWORD_HASH_ENV] = generate_password_hash(password)
    values.setdefault(SECRET_KEY_ENV, secrets.token_hex(32))


def main() -> int:
    env_path = paths.env_path()
    values = read_env(env_path)
    password = os.environ.get(SETUP_PASSWORD_ENV, "")
    if password:
        print("Using the password from the environment (unattended install).")
    else:
        password = getpass.getpass("New household password: ")
        if not password:
            print("Password can't be empty.", file=sys.stderr)
            return 1
        if getpass.getpass("Confirm password: ") != password:
            print("Passwords didn't match.", file=sys.stderr)
            return 1
    apply_password(values, password)
    env_path.parent.mkdir(parents=True, exist_ok=True)
    write_env(env_path, values)
    print(f"Saved password hash to {env_path}. Restart the app to apply.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
