"""Creates (or rotates) the token Home Assistant uses to call the Meal
Planner's voice endpoints on behalf of the Alexa skill.

Writes MEAL_PLANNER_API_TOKEN into the same secrets file set_password.py
manages and prints the token once, ready to paste into Home Assistant's
secrets.yaml as  meal_planner_auth: "Bearer <token>". Run it again to
rotate; the old token stops working when the app restarts.

    python set_api_token.py
"""
import secrets
import sys

import paths
from set_password import read_env, write_env

API_TOKEN_ENV = "MEAL_PLANNER_API_TOKEN"


def new_api_token(values: dict[str, str]) -> str:
    """Always rotate: store and return a fresh token."""
    token = secrets.token_hex(32)
    values[API_TOKEN_ENV] = token
    return token


def ensure_api_token(values: dict[str, str]) -> str:
    """Keep an existing token, else create one."""
    return values.get(API_TOKEN_ENV) or new_api_token(values)


def main() -> int:
    env_path = paths.env_path()
    values = read_env(env_path)
    token = new_api_token(values)
    env_path.parent.mkdir(parents=True, exist_ok=True)
    write_env(env_path, values)
    print(f"Saved a new voice API token to {env_path}. Restart the app to apply.")
    print("Put this line in Home Assistant's secrets.yaml (see alexa/SETUP.md):")
    print(f'  meal_planner_auth: "Bearer {token}"')
    return 0


if __name__ == "__main__":
    sys.exit(main())
