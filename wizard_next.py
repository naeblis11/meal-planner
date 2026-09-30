"""What's left to do after setup: the Home Assistant YAML and a numbered checklist."""
import textwrap
from pathlib import Path

import set_api_token

APP_ROOT = Path(__file__).resolve().parent
PLACEHOLDER_URL = "http://meal-planner.local:5000"


# Pi + remote access + no Tailscale auth key: setup-tailscale.sh only installs
# Tailscale when it is given a key, so it has to be done by hand once.
PI_TAILSCALE_STEPS = (
    "Then, once the Pi is up, connect it to Tailscale:",
    "  1. ssh <user>@<name>.local",
    "  2. curl -fsSL https://tailscale.com/install.sh | sh",
    "  3. sudo tailscale up --ssh",
    "  4. sudo ~/Meal_Planner/pi/setup-tailscale.sh",
)


def _render(relative: str, app_url: str) -> str:
    text = (APP_ROOT / relative).read_text(encoding="utf-8")
    return text.replace(PLACEHOLDER_URL, app_url.rstrip("/"))


def render_ha_yaml(app_url: str) -> str:
    return _render("ha/shopping-list.yaml", app_url)


def render_alexa_yaml(app_url: str) -> str:
    return _render("alexa/home-assistant.yaml", app_url)


def _block(text: str) -> str:
    return "\n" + textwrap.indent(text.rstrip("\n"), "    ") + "\n"


def checklist(values: dict, features: dict, app_url: str, *, pi_name: str = "",
              tailscale_key_given: bool = True) -> str:
    steps = [f"Open {app_url} and sign in with the household password."]
    secrets_line = f'meal_planner_auth: "Bearer {values.get(set_api_token.API_TOKEN_ENV, "")}"'
    secrets_shown = False

    if features.get("remote"):
        name = pi_name or "this-pc-name"
        text = ("On each phone, install Tailscale and sign in with the same account, then open "
                f"https://{name}.<your-tailnet>.ts.net (the Tailscale admin console's "
                "Machines page shows the exact name).")
        if pi_name and not tailscale_key_given:
            text += "\nFirst connect the Pi to Tailscale, one time:\n" + "\n".join(
                line.replace("<name>", pi_name) for line in PI_TAILSCALE_STEPS[1:])
        steps.append(text)
    if features.get("ha"):
        steps.append("In Home Assistant, add this line to secrets.yaml:\n"
                     + _block(secrets_line)
                     + "\nthen add this package (full steps: ha/SETUP-SHOPPING.md):\n"
                     + _block(render_ha_yaml(app_url)))
        secrets_shown = True
    if features.get("alexa"):
        text = ("Add the Alexa bridge (alexa/home-assistant.yaml, filled in below) and "
                "follow alexa/SETUP.md to create the skill.\n"
                + _block(render_alexa_yaml(app_url)))
        if not secrets_shown:
            text += ("\nIt uses this line in Home Assistant's secrets.yaml:\n"
                     + _block(secrets_line))
        steps.append(text)
    if features.get("gcal"):
        steps.append("The meal plan page now has a 'Send this week to Google Calendar' button.")
    if not pi_name and values.get("MEAL_PLANNER_HOST") == "0.0.0.0":
        steps.append("The first time the app starts, Windows Firewall asks about it: allow "
                     "\"Private networks\", or phones on your home network won't reach the app.")
    steps.append("Chrome extension: chrome://extensions -> Developer mode -> Load unpacked -> "
                 f"the chrome-extension folder; then its Settings -> address {app_url}")
    if pi_name:
        steps.append("To change any of this later: ssh <user>@" + pi_name
                     + ".local, then cd Meal_Planner && .venv/bin/python configure.py")
    else:
        steps.append("To change any of this later: run configure.py again.")

    lines = ["What's left to do:", ""]
    for number, step in enumerate(steps, 1):
        lines.append(f"{number}. {step}")
        lines.append("")
    return "\n".join(lines).rstrip("\n")
