"""Guided setup for the Meal Planner: asks a few questions, then saves the settings.

On Windows it first asks whether the app goes on this PC or on a Raspberry Pi
(whose SD card it prepares); on Linux it configures the machine it runs on.
Nothing is saved until you confirm at the end. Run it again any time to change
answers.

    python configure.py [--no-restart] [--report PATH]

--no-restart   do not restart the meal-planner service after saving (Linux)
--report PATH  write the chosen target ("windows" or "pi") to PATH
"""
import argparse
import socket
import subprocess
import sys
from pathlib import Path

import gcal
import paths
import set_gcal
import set_password
import wizard_io
import wizard_next
import wizard_steps

APP_DIR = Path(__file__).resolve().parent
SERVICE_UNIT = Path("/etc/systemd/system/meal-planner.service")


def _on_off(flag) -> str:
    return "on" if flag else "off"


def summarize(values: dict, ctx) -> list[str]:
    home = values.get(wizard_steps.HOST_ENV) == wizard_steps.ALL_INTERFACES
    return [
        f"Password: {'set' if values.get(set_password.PASSWORD_HASH_ENV) else 'not set'}",
        f"Reachable from: {'your home network' if home else 'this computer only'}",
        f"Remote access (Tailscale): {_on_off(ctx.features.get('remote'))}",
        f"Home Assistant shopping list: {_on_off(ctx.features.get('ha'))}",
        f"Alexa voice skill: {_on_off(ctx.features.get('alexa'))}",
        f"Google Calendar: {_on_off(ctx.features.get('gcal'))}",
        f"Settings file: {paths.env_path()}",
    ]


def _app_url(values) -> str:
    if values.get(wizard_steps.HOST_ENV):
        return f"http://{socket.gethostname().lower()}.local:5000"
    return "http://127.0.0.1:5000"


def _show_next(con, values, ctx) -> None:
    con.say()
    con.say(wizard_next.checklist(values, ctx.features, _app_url(values)))


def _after_save(ctx, args, run_command) -> None:
    if ctx.features.get("remote"):
        cmd = ["sudo", "bash", str(APP_DIR / "pi" / "setup-tailscale.sh")]
        if ctx.tailscale_key:
            cmd.append(ctx.tailscale_key)
        run_command(cmd)
    if not args.no_restart and SERVICE_UNIT.exists():
        run_command(["sudo", "systemctl", "restart", "meal-planner"])


def main(argv=None, *, console=None, platform: str = sys.platform,
         make_context=wizard_steps.Context, run_command=subprocess.run) -> int:
    parser = argparse.ArgumentParser(description="Guided Meal Planner setup.")
    parser.add_argument("--no-restart", action="store_true")
    parser.add_argument("--report", metavar="PATH")
    args = parser.parse_args(argv)
    con = console or wizard_io.Console()
    try:
        con.heading("Meal Planner setup")
        con.say("Answers can be changed later by running this again. "
                "Nothing is saved until the end.")
        if platform == "win32":
            choice = con.choose("Where will the app run?", [
                "This Windows PC", "A Raspberry Pi (I'll prepare its SD card here)"])
            target = wizard_steps.TARGET_WINDOWS if choice == 1 else wizard_steps.TARGET_PI
        else:
            target = wizard_steps.TARGET_LINUX
        if args.report:
            Path(args.report).write_text(target + "\n", encoding="utf-8")
        if target == wizard_steps.TARGET_PI:
            import wizard_pi
            return wizard_pi.run(con, make_context(target=wizard_steps.TARGET_PI))

        env_path = paths.env_path()
        values = set_password.read_env(env_path)
        ctx = make_context(target=target)
        for step in wizard_steps.STEPS:
            step(con, values, ctx)
        con.heading("Summary")
        for line in summarize(values, ctx):
            con.say(line)
        if not con.yes_no("Save these settings?", True):
            con.say("Nothing saved.")
            return 1
        if ctx.gcal_key_source:
            values[gcal.CREDENTIALS_ENV] = str(set_gcal.store_key(ctx.gcal_key_source))
        env_path.parent.mkdir(parents=True, exist_ok=True)
        set_password.write_env(env_path, values)
    except KeyboardInterrupt:
        con.say("\nStopped. Nothing was saved.")
        return 1
    if target == wizard_steps.TARGET_LINUX:
        _after_save(ctx, args, run_command)
    _show_next(con, values, ctx)
    return 0


if __name__ == "__main__":
    sys.exit(main())
