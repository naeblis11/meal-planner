"""Links the Meal Planner to a Home Assistant to-do list so the shopping
list can be used from the HA companion app anywhere (see
docs/superpowers/specs/2026-09-14-remote-access-design.md, Phase 1).

Asks for Home Assistant's URL as seen from this PC and a long-lived
access token (created under your HA user profile -> Security), proves
the link with one todo.get_items call, then writes
MEAL_PLANNER_HA_URL / MEAL_PLANNER_HA_TOKEN / MEAL_PLANNER_HA_TODO_ENTITY
into the same secrets file set_password.py manages. Run it again to
change any of them. Restart the app afterwards.

    python set_ha_link.py
"""
import getpass
import sys

import ha_sync
import paths
from set_password import read_env, write_env

HA_URL_ENV = "MEAL_PLANNER_HA_URL"
HA_TOKEN_ENV = "MEAL_PLANNER_HA_TOKEN"
HA_TODO_ENTITY_ENV = "MEAL_PLANNER_HA_TODO_ENTITY"

HA_KEYS = (HA_URL_ENV, HA_TOKEN_ENV, HA_TODO_ENTITY_ENV)


def check_ha_link(url: str, token: str, entity: str, make_link=ha_sync.HALink) -> int:
    """Prove the link with one call; returns the item count. Raises ha_sync.HAError."""
    return len(make_link(url, token, entity).get_items())


def apply_ha_link(values: dict[str, str], url: str, token: str, entity: str) -> None:
    values[HA_URL_ENV] = url
    values[HA_TOKEN_ENV] = token
    values[HA_TODO_ENTITY_ENV] = entity


def clear_ha_link(values: dict[str, str]) -> None:
    for key in HA_KEYS:
        values.pop(key, None)


def main() -> int:
    env_path = paths.env_path()
    values = read_env(env_path)

    current_url = values.get(HA_URL_ENV, "http://homeassistant.local:8123")
    url = input(f"Home Assistant URL [{current_url}]: ").strip() or current_url
    token = getpass.getpass("Long-lived access token (leave blank to keep the saved one): ").strip()
    token = token or values.get(HA_TOKEN_ENV, "")
    if not token:
        print("A token is required. Create one in Home Assistant under your profile -> Security.",
              file=sys.stderr)
        return 1
    current_entity = values.get(HA_TODO_ENTITY_ENV, ha_sync.DEFAULT_ENTITY)
    entity = input(f"To-do list entity id [{current_entity}]: ").strip() or current_entity

    try:
        count = check_ha_link(url, token, entity)
    except ha_sync.HAError as exc:
        print(f"Could not reach that list: {exc}", file=sys.stderr)
        print("Nothing saved. Check the URL, the token, and that the Local To-do list exists.",
              file=sys.stderr)
        return 1

    apply_ha_link(values, url, token, entity)
    env_path.parent.mkdir(parents=True, exist_ok=True)
    write_env(env_path, values)
    print(f"Linked to {entity} at {url} ({count} item(s) there now). Saved to {env_path}.")
    print("Restart the app to start mirroring the shopping list.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
