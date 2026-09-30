# Running the Meal Planner on a Raspberry Pi

The installer (`install.ps1` / `configure.py`) does the app-side steps below for you; this page is the manual route and the reference.

The Pi and the Windows PC run the **same code** — one repository, no
separate version. The only differences are where per-machine files
live and how the app is kept running:

| | Windows PC | Raspberry Pi |
|---|---|---|
| Secrets (`.env`) | `%LOCALAPPDATA%\Meal Planner\.env` | `~/.config/meal-planner/.env` |
| Recipes, database, photos | `Documents\Meal Planner\` | `~/meal-planner/` |
| Starting the app | `python app.py` in a terminal | systemd service — starts at boot, restarts itself |
| Updating | `git pull`, restart `app.py` | `./pi/update.sh` |

Run it on one or the other, not both at once: each has its own recipe
library, and the Home Assistant mirror expects one app.

**Which Pi?** Tested on Raspberry Pi OS (Bookworm, **64-bit**) on a Pi 5.
A Pi 4, or a Pi 3 Model B / B+, works as well -- all three are 64-bit
chips, and the app is small: Flask, SQLite and your recipe files. On a Pi 3
expect the unattended install to take nearer 20 minutes than 10, and pages
to be a beat slower to draw; nothing else differs. Anything with a 64-bit
Pi OS and Python 3.11+ should be fine.

Two things that are *not* optional: the 64-bit OS (that is all the SD-card
kit and these instructions are tested against), and a decent SD card -- a
slow or worn card is far more noticeable here than the model of Pi.

## The easy way: let the installer do it

From a Windows PC, in the project folder:

    powershell -ExecutionPolicy Bypass -File install.ps1

Choose **A Raspberry Pi**. The installer:

1. asks what to call the Pi (default `meal-planner`, so the app ends up at
   `http://meal-planner.local:5000`);
2. walks you through Raspberry Pi Imager step by step (model, Raspberry Pi
   OS Lite 64-bit, your card, and the hostname, login, Wi-Fi and SSH
   settings) and waits while you write the card;
3. asks the same questions as a PC install: the household password, who
   can reach the app, and each optional feature (remote access, Home
   Assistant, Alexa, Google Calendar), explaining where to get each token;
4. writes your settings onto the card with `pi/prepare-sd.ps1` (the
   password is stored as a hash), and tells you to put the card in the Pi
   and power it on;
5. waits for the Pi to install itself (10-20 minutes), then prints what is
   left to do and saves it to a text file on your Desktop. That file holds
   your API token, so delete it once you have finished.

You need a Pi 4 or 5 with its power supply, a microSD card of 16 GB or more
and a card reader. To change a setting later, sign in with
`ssh <user>@meal-planner.local`, then `cd Meal_Planner` and run
`.venv/bin/python configure.py`; it restarts the service when you save.

**Internet access on first boot.** The Pi downloads the app from the
public GitHub repository the first time it starts, so it needs internet
access and the repository must be reachable without signing in. To install
from a fork instead, change `REPO_URL` in `mealplanner.conf` (the kit file
on the card) before you insert it.

## The same thing by hand

The installer runs the steps below for you. Do them yourself if you want
to script it or see what is happening. No keyboard, monitor or SSH is
needed on the Pi; you do everything from Windows before the card goes in.

1. **Flash the card.** [Raspberry Pi Imager](https://www.raspberrypi.com/software/):
   choose your model (Pi 5, Pi 4 or Pi 3), OS *Raspberry Pi OS Lite
   (64-bit)*, your card, then **click the gear / "Edit settings"** and set:
   - hostname `meal-planner` (the app is then at `http://meal-planner.local:5000`)
   - a username and password (remember them -- that is your SSH login)
   - your Wi-Fi network and password, unless you will use Ethernet
   - **Services -> Enable SSH** (password or key, your choice)

   Write the card, and leave it in the reader when Imager finishes.

   **No monitor and no Ethernet?** That is the normal case, and this screen
   is what makes it work: the Wi-Fi details here are the only way the Pi can
   reach anything on its first boot. Get the network name, password and
   country right. (If you would rather not re-flash a card you have already
   written, step 2 can set the Wi-Fi instead -- see `-WifiSsid` below.)

2. **Add the Meal Planner kit.** In PowerShell, in the project folder on
   your PC:

       .\pi\prepare-sd.ps1

   It finds the card, asks for the household password, and copies three
   small files onto it. To set up remote access at the same time, make an
   auth key at https://login.tailscale.com/admin/settings/keys and pass it:

       .\pi\prepare-sd.ps1 -TailscaleAuthKey 'tskey-auth-...'

   To put Wi-Fi on a card that was flashed without it -- no re-flash, no
   monitor -- add the network too. The Pi writes itself a NetworkManager
   profile before it needs the network:

       .\pi\prepare-sd.ps1 -WifiSsid 'HOMELAN' -WifiPassword '...' -WifiCountry US

   (This does not create the user account or enable SSH, which only Imager
   can do. If the card was flashed with no Imager settings at all, re-flash
   it -- the Pi would have no way in.)

3. **Eject the card, put it in the Pi, power on.** The first boot installs
   everything -- packages, the app, its service, and Tailscale if you gave
   a key -- which takes 5-10 minutes on a Pi 5, longer on a Pi 3. When it
   finishes the app is
   at `http://meal-planner.local:5000`, already running.

   Watch it if you like: `ssh <user>@meal-planner.local` then
   `sudo journalctl -u mealplanner-provision -f`, or read
   `/var/log/mealplanner-provision.log` afterwards.

The Pi then looks after itself: the app starts at boot, restarts if it
ever crashes, and the Pi powers back on by itself after a power cut, so
everything comes back with no intervention. Tailscale runs as a service
and reconnects on its own; `tailscale serve` settings survive reboots.

Secrets you put on the card are wiped from it once used, so the card is
not left carrying your password.

If you install Tailscale on the Pi *after* that first boot -- by hand, or
because the auth key had expired -- there is nothing to redo. First-boot
provisioning runs once and only once, so it cannot publish a Tailscale
that did not exist yet; `mealplanner-tailscale.service` runs
`pi/setup-tailscale.sh` at every boot instead and picks it up then. To
avoid waiting for a reboot, run `sudo ./pi/setup-tailscale.sh` yourself.

### Doing it all remotely later

Everything above can also be changed from your desk, with no screen on
the Pi:

    ssh <user>@meal-planner.local                       # on the home network
    ssh <user>@meal-planner                             # from anywhere, via Tailscale SSH

    cd Meal_Planner
    .venv/bin/python set_password.py              # change the household password
    .venv/bin/python set_api_token.py             # new Home Assistant voice token
    .venv/bin/python set_ha_link.py               # link the shopping-list mirror
    sudo tailscale up                             # re-authenticate an expired key
    sudo ./pi/setup-tailscale.sh                  # publish to the tailnet
    sudo systemctl restart meal-planner           # apply changes

## Install on the Pi itself (an existing Pi, or Linux without Windows)

On the Pi, as your normal user (not root):

    sudo apt install -y git
    git clone https://github.com/naeblis11/meal-planner.git Meal_Planner
    cd Meal_Planner
    ./pi/install.sh

The script installs the two system packages it needs (`python3-venv`,
the JPEG library for photos), builds a Python environment inside the
project folder, asks the same questions as the Windows installer (household password, then each optional feature) by running
`configure.py`, and installs a `meal-planner` systemd service. It ends by printing the address —
`http://<pi-name>.local:5000` — and where your recipes live. Every step
says what it's doing; if one fails, the message names it.

Re-running the script is safe (it keeps your password and data).

## Moving your library from the PC

Copy `Documents\Meal Planner\recipes\` and `recipe-images\` from the
PC to `~/meal-planner/` on the Pi (over the network, a USB stick, or
`scp`), then either restart the service or click **Rescan recipes/
folder** in the app. The database rebuilds itself from the YAML files;
don't copy `mealplanner.db`. Pantry, meal plan and shopping list live in
that database, so note them down first if you want to recreate them.

## Day to day

    sudo systemctl status meal-planner     # is it running?
    journalctl -u meal-planner -f          # live log
    sudo systemctl restart meal-planner    # after editing .env
    ./pi/update.sh                         # pull the latest code and restart
    .venv/bin/python set_password.py       # change the household password

The app is on the network as soon as it starts (`MEAL_PLANNER_HOST=0.0.0.0`
is set by the installer), so the kitchen phone opens
`http://<pi-name>.local:5000` directly.

## The other integrations

- **Chrome extension**: open its **Settings** (link at the bottom of the
  popup) and set the address to `http://<pi-name>.local:5000`. Chrome
  asks once to allow it.
- **Home Assistant** (Alexa skill and the shopping-list mirror): the
  `rest_command` URLs in your HA configuration must point at
  `<pi-name>.local:5000`. Then on
  the Pi run `.venv/bin/python set_api_token.py` (voice token) and
  `.venv/bin/python set_ha_link.py` (shopping-list mirror), paste the
  new voice token into HA's `secrets.yaml`, and restart the service.
- **Away from home (Tailscale)**: `sudo ./pi/setup-tailscale.sh` does the
  lot -- installs Tailscale if it is missing, signs in if you give it an
  auth key, publishes the app to your tailnet over HTTPS, and sets
  `MEAL_PLANNER_BEHIND_PROXY=1` in the Pi's `.env` so logging in over
  HTTPS works. The address becomes `https://<pi-name>.<tailnet>.ts.net`.
  See `docs/REMOTE-ACCESS.md` for what it is doing and why.

## Backups

Your library is plain files in `~/meal-planner/`. Back that folder
up however you back up the Pi — a nightly `rsync` to the NAS, or
`git init` inside it and push to a private repo. The database is
rebuilt from the YAML on every start, so `recipes/` and `recipe-images/`
are what matter.

## If something's off

- **`bad interpreter: /bin/bash^M`**: the scripts got Windows line
  endings. `git config core.autocrlf false && git checkout -- pi/` on the
  Pi fixes it (the repo pins them to LF; this only happens with an
  unusual clone).
- **First boot seems to do nothing**: it needs a network. Check the Wi-Fi
  details in Imager, or plug in Ethernet, then
  `sudo systemctl start mealplanner-provision` over SSH. The log is
  `/var/log/mealplanner-provision.log`.
- **The service won't stay up**: `journalctl -u meal-planner -n 50`
  shows why. The usual one is the secrets file missing — run
  `.venv/bin/python set_password.py`.
- **`<pi-name>.local` doesn't resolve from a phone**: mDNS is on by
  default in Pi OS (avahi); from Windows it needs Bonjour or a modern
  build. Fall back to the Pi's IP address (`hostname -I`) and give it a
  DHCP reservation in the router.
- **Photos fail to upload with a Pillow/JPEG error**: `sudo apt install
  libjpeg62-turbo` and restart the service.
