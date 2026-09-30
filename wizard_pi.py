"""Guided Raspberry Pi setup, run from the Windows installer.

The walkthrough: name the Pi, flash the SD card with Raspberry Pi Imager, answer
the same questions as the PC install, write the settings (password already
hashed) onto the card with pi/prepare-sd.ps1, power the Pi on, wait for it to
install itself, then print and save what is left to do. Everything shown to the
user is written for someone who has never used a Pi.
"""
import json
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

import paths
import set_password
import wizard_next
import wizard_steps

APP_ROOT = Path(__file__).resolve().parent
HOSTNAME_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
DEFAULT_NAME = "meal-planner"
CHECKLIST_FILE = "Meal Planner - Pi setup steps.txt"
CHECKLIST_WARNING = "This file contains your Meal Planner API token. Delete it once setup is done."


class CardError(Exception):
    """Preparing the SD card failed."""


def ask_pi_name(con) -> str:
    con.say("Give the Pi a name. You will use it to reach the app, for example "
            f"http://{DEFAULT_NAME}.local:5000. Use only letters, numbers and dashes.")
    while True:
        name = con.ask("Name for the Pi", DEFAULT_NAME).lower()
        if HOSTNAME_RE.match(name):
            return name
        con.say("That name has characters a Pi can't use. Use letters, numbers and dashes "
                "only, with no spaces, and don't start or end with a dash.")


def show_imager_steps(con, name: str) -> None:
    con.heading("Put the operating system on the SD card")
    con.say("Put the microSD card in your card reader and plug it into this PC. Then:")
    con.say()
    steps = [
        (1, "Download and install Raspberry Pi Imager from https://www.raspberrypi.com/software/ "
            "and open it."),
        (2, "Choose Device: pick your Pi model (Pi 4 or Pi 5)."),
        (3, "Choose OS: Raspberry Pi OS (other), then Raspberry Pi OS Lite (64-bit)."),
        (4, "Choose Storage: pick the SD card. Check the size carefully, everything on it "
            "will be erased."),
        (5, "Click Next, then Edit settings, and fill in:"),
        (0, f"- Hostname: {name}"),
        (0, "- Username and password: these are for signing in to the Pi itself over SSH. "
            "They are NOT the app's password. Write them down."),
        (0, "- Wi-Fi name, password and country (skip this if the Pi will use a network cable)."),
        (0, "- Time zone and keyboard layout."),
        (0, "- Services tab: turn on Enable SSH, and choose password authentication."),
        (6, "Click Save, then Yes, then Yes again to erase the card and write it."),
    ]
    for number, text in steps:
        con.say(f"  {number}. {text}" if number else f"       {text}")
    con.say()
    con.say("When it finishes, take the card out and put it back in. "
            "If Windows offers to format a drive, click Cancel.")
    con.pause("Press Enter once the card is written and back in the PC...")


def write_card(values: dict, ctx, *, repo_root: Path = APP_ROOT,
               run=subprocess.run, drive: str = "") -> None:
    tmp = tempfile.mkdtemp()
    try:
        secrets = Path(tmp) / "secrets.env"
        set_password.write_env(secrets, values)
        argv = ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                str(repo_root / "pi" / "prepare-sd.ps1"), "-SecretsFile", str(secrets)]
        if drive:
            argv += ["-Drive", drive]
        if ctx.gcal_key_source:
            argv += ["-GcalKeyFile", str(ctx.gcal_key_source)]
        if ctx.tailscale_key:
            argv += ["-TailscaleAuthKey", ctx.tailscale_key]
        result = run(argv)
        if result.returncode != 0:
            raise CardError("preparing the card failed (see the message above)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def wait_for_app(url: str, *, timeout: float = 1200, interval: float = 5,
                 opener=urllib.request.urlopen, clock=time.monotonic, sleep=time.sleep,
                 on_tick=lambda elapsed: None) -> bool:
    start = clock()
    while clock() - start < timeout:
        try:
            response = opener(url + "/healthz", timeout=5)
            try:
                body = response.read()
            finally:
                getattr(response, "close", lambda: None)()
            if json.loads(body).get("ok"):
                return True
        except (urllib.error.URLError, OSError, ValueError, AttributeError):
            pass
        on_tick(clock() - start)
        sleep(interval)
    return False


def save_checklist(text: str, desktop: Path | None = None) -> Path:
    folder = desktop or paths.desktop_dir()
    if not folder.is_dir():
        if desktop is None:
            folder = Path.home()  # no Desktop here (e.g. a Linux box): don't invent one
        else:
            folder.mkdir(parents=True, exist_ok=True)
    path = folder / CHECKLIST_FILE
    path.write_text(f"{CHECKLIST_WARNING}\n\n{text}\n", encoding="utf-8")
    return path


def _wait_with_progress(con, url: str) -> bool:
    printed = {"minute": 0}

    def tick(elapsed):
        minute = int(elapsed // 60)
        if minute > printed["minute"]:
            printed["minute"] = minute
            con.say(f"  still installing... {minute} min")

    return wait_for_app(url, on_tick=tick)


def _write_until_done(con, values, ctx) -> bool:
    drive = ""
    while True:
        try:
            write_card(values, ctx, drive=drive)
            return True
        except CardError as exc:
            con.say(f"Problem: {exc}.")
        choice = con.choose("What now?", ["Try again", "Enter the drive letter myself", "Stop"])
        if choice == 3:
            return False
        if choice == 2:
            drive = con.ask("Drive letter, e.g. E:")


def run(con, ctx) -> int:
    con.heading("Set up a Raspberry Pi")
    con.say("You will need:")
    con.say("  - a Raspberry Pi 4 or 5 with its power supply")
    con.say("  - a microSD card, 16 GB or bigger, and a card reader for this PC")
    con.say("  - a network cable, or your Wi-Fi name and password")
    written = False
    try:
        name = ask_pi_name(con)
        show_imager_steps(con, name)
        values: dict = {}
        for step in wizard_steps.STEPS:
            step(con, values, ctx)

        import configure  # lazy: configure imports this module
        con.heading("Ready to write the settings")
        for line in configure.summarize(values, ctx):
            if not line.startswith("Settings file:"):
                con.say(f"  {line}")
        con.say("Settings go onto the SD card; the Pi deletes them from the card after installing.")
        if not con.yes_no("Write these to the SD card now?", True):
            con.say("Nothing written.")
            return 1
        if not _write_until_done(con, values, ctx):
            con.say("Nothing more was done. Run the installer again when you're ready.")
            return 1
        written = True

        con.say()
        con.say("Eject the card (right-click the drive in File Explorer, then Eject), put it in "
                "the Pi, connect the network cable if you use one, and switch it on. "
                "The first start installs everything and takes 10-20 minutes.")
        con.pause("Press Enter once the Pi is switched on...")

        url = f"http://{name}.local:5000"
        while True:
            con.say(f"Waiting for {url} ...")
            if _wait_with_progress(con, url):
                con.say("The Pi is up.")
                break
            con.say("The Pi hasn't answered yet. Things to check:")
            con.say("  - A screen on the Pi shows install progress, if you have one plugged in.")
            con.say(f"  - Sign in with ssh <user>@{name}.local (the username you chose in Imager), "
                    "then run: sudo journalctl -u mealplanner-provision -f")
            con.say("  - Check the Wi-Fi name, password and country you entered in Imager.")
            con.say(f"  - http://{name}.local only works when this PC and the Pi are on the same network.")
            if con.choose("What now?", ["Keep waiting", "Finish anyway"]) == 2:
                break

        text = wizard_next.checklist(values, ctx.features, url, pi_name=name,
                                     tailscale_key_given=bool(ctx.tailscale_key))
        con.heading("What's left to do")
        con.say(text)
        try:
            path = save_checklist(text)
            con.say(f"Saved a copy to {path}")
        except OSError as exc:
            con.say(f"Couldn't save a copy of this list ({exc}); it is printed above.")
        return 0
    except KeyboardInterrupt:
        con.say()
        if written:
            con.say("Stopped waiting. The card is ready and the Pi will finish installing on its own.")
            return 0
        con.say("Stopped. Nothing was written to the SD card.")
        return 1
