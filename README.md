# Meal Planner

A self-hosted recipe library, weekly meal calendar, pantry, and pantry-aware shopping list for one household. Recipes are plain [Open Recipe Format](https://github.com/open-recipe-format) YAML files you own; the app runs on a Windows PC or a Raspberry Pi at home and is used from any browser in the house — or, if you turn on remote access, from your phone in the grocery store.

## Features

- **Recipe library** — browse, search, and categorize recipes stored as YAML files. Import new recipes from `.yaml`/`.yml` files, legacy Meal Master (`.mmf`) files, or directly from a recipe website via the companion [Chrome extension](chrome-extension/).
- **Ingredient editing** — add, edit, delete, and reorganize a recipe's ingredients into named sections (e.g. "Bechamel Sauce", "Bolognese Sauce") right from the recipe page, including moving ingredients between sections.
- **Unit conversion** — ingredient amounts are normalized to a single (US customary/imperial) system of units at import time, with fraction-exact arithmetic — no floating-point rounding drift. Amounts the parser can't understand are flagged for a quick manual correction instead of silently guessing.
- **Meal calendar** — assign recipes to a date and meal slot for the week ahead.
- **Pantry** — track what you already have on hand.
- **Shopping list** — a running cart: add a week's assigned meals to it (as many weeks as you like) and it crosses them against your pantry, automatically combining matching ingredients (with unit conversion) so you don't see "2 tbsp butter" and "1 cup butter" as two separate lines. Nothing leaves the list until you check it off, remove it, or clear the list.
- **Shopping list on your phone, anywhere (optional)** — the list is mirrored into a Home Assistant to-do list, so in the store you tick things off in the HA companion app (over your Home Assistant remote connection) and the ticks show up at home; add or delete items there too. See [ha/SETUP-SHOPPING.md](ha/SETUP-SHOPPING.md).
- **Voice control (optional)** — an Alexa skill ("Alexa, ask my chef to add milk to the cart / add olive oil to the pantry / plan tacos for dinner on Thursday") bridged through your home's Home Assistant. See [alexa/SETUP.md](alexa/SETUP.md).
- **Recipe photos** — attach a photo to a recipe (uploaded manually or pulled in automatically by the Chrome extension).

## Install

You need a Windows PC for the installer, even if the app will live on a Raspberry Pi.

1. Download this repository (green **Code** button → **Download ZIP**, then extract it somewhere permanent such as `C:\Meal Planner`), or `git clone https://github.com/naeblis11/meal-planner.git`.
2. Open the folder that contains `install.ps1` (a downloaded ZIP extracts into a nested folder such as `meal-planner-master`), click the address bar, type `powershell` and press Enter.
3. Run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File install.ps1
   ```

The installer gets Python if you don't have it (if it has to install Python, it asks you to close the window and run the same command again from a new PowerShell window), then asks:

- **Where the app runs** — this PC, or a Raspberry Pi. For a Pi you also need a Pi 4 or 5 with its power supply, a microSD card (16 GB or more) and a card reader; the installer walks you through writing the card with Raspberry Pi Imager, puts your settings on it, and waits for the Pi to finish installing itself.
- **A household password** — everyone signs in with it.
- **Who can reach it** — just this computer, or everything on your home network.
- **Optional features**, each of which you can turn on or off, with instructions for getting whatever it needs:

| Feature | What it gives you | What you'll need |
|---|---|---|
| Remote access | The whole app on your phone anywhere, over HTTPS | A free [Tailscale](https://tailscale.com) account, Tailscale on each phone |
| Home Assistant shopping list | Tick items off in the Home Assistant app in the store | Home Assistant, a long-lived access token, a Local To-do list |
| Alexa voice skill | "Alexa, ask my chef to add milk" | Home Assistant reachable by Alexa (e.g. Nabu Casa), an Amazon developer account — see [alexa/SETUP.md](alexa/SETUP.md) |
| Google Calendar | The week's meals on a shared calendar | A Google Cloud project and a service account key, or your own Google sign-in — see [docs/GOOGLE-CALENDAR.md](docs/GOOGLE-CALENDAR.md) |

At the end it prints (and for a Pi, saves to your Desktop) what's left to do, such as the Home Assistant configuration with your values filled in.

**Changing settings later:** run `configure.py` again — on Windows `.venv\Scripts\python configure.py` in the app folder; on a Pi, `ssh <user>@<pi-name>.local`, then `cd Meal_Planner && .venv/bin/python configure.py`.

**Linux without Windows:** `git clone https://github.com/naeblis11/meal-planner.git Meal_Planner && cd Meal_Planner && ./pi/install.sh` asks the same questions on the Pi itself. [docs/RASPBERRY-PI.md](docs/RASPBERRY-PI.md) has the details.

### Where your data lives

Secrets (a salted hash of the household password, a session-signing key, and any optional-feature settings) are kept in `%LOCALAPPDATA%\Meal Planner\.env` on Windows, or `~/.config/meal-planner/.env` on Linux — never in the project folder (see `.env.example`; set `MEAL_PLANNER_HOME` to put that folder elsewhere). The app refuses to start until that file exists.

Your recipe library is created on first run at `Documents\Meal Planner\` (honouring a Documents folder redirected into OneDrive), or `~/meal-planner/` on Linux. It starts empty; any `.yaml` files in this repo's `recipes/` folder are copied in as starter content on that first run. Everything you add afterwards — recipes, photos, meal plans, pantry, shopping list — lives there, never in the install folder, so reinstalling or upgrading can't touch it. Set `MEAL_PLANNER_DATA_DIR` to use a different folder.

Add your own recipes by dropping `.yaml` files into the library's `recipes/` folder (only `.yaml` is auto-indexed; `.yml` is ignored) and clicking "Rescan recipes/ folder" in the app, or by using the in-app import screen for `.yaml`/`.yml`/`.mmf` uploads.

### Running it by hand

The installer can start the app for you and add it to Windows Startup. To run it yourself, use `.venv\Scripts\python app.py` on Windows or `.venv/bin/python app.py` on Linux (the installer's virtual environment, not a bare `python`) and not `flask run` (the startup sync and the [Waitress](https://docs.pylonsproject.org/projects/waitress/) server live there) and browse to `http://127.0.0.1:5000`. Every page sits behind the sign-in screen; the session lasts 30 days per browser. Don't port-forward port 5000 to the internet: the household password is the only thing between the internet and your data. To use the app away from home, turn on remote access in the installer; [docs/REMOTE-ACCESS.md](docs/REMOTE-ACCESS.md) explains what it does.

## Requirements

The installer fetches what it can. This is the full list of tools the installer, the app, and the tests may use.

| Requirement | Needed for | How to get it |
|---|---|---|
| **Python 3.11+** | Running the app, all Python tests | The installer offers to `winget install Python.Python.3.12` if it's missing; or [python.org](https://www.python.org/downloads/) (tick "Add python.exe to PATH"). Raspberry Pi OS Bookworm already has it |
| **Python packages** (`requirements.txt`) | Running the app | Installed by `install.ps1` into a `.venv` (or `pip install -r requirements.txt`): Flask, PyYAML, Pillow (photo resizing), python-dotenv (reads the secrets file), Waitress (the web server), and cryptography (signs in to Google Calendar; only used if you link one) |
| **Raspberry Pi Imager** (Pi installs only) | Writing Raspberry Pi OS to the SD card | [raspberrypi.com/software](https://www.raspberrypi.com/software/) — the installer tells you when |
| **Raspberry Pi 4 or 5, microSD card, card reader** (Pi installs only) | Running the app as an always-on service | See [docs/RASPBERRY-PI.md](docs/RASPBERRY-PI.md) |
| **Tailscale** (optional) | Using the whole app away from home over an encrypted private network, with HTTPS | [tailscale.com/download](https://tailscale.com/download) on each phone (and the PC, if the app runs on one); free personal plan. See [docs/REMOTE-ACCESS.md](docs/REMOTE-ACCESS.md) |
| **Home Assistant** (optional) | The shopping-list mirror and the Alexa skill | [home-assistant.io](https://www.home-assistant.io/), reachable from your phone away from home (for example with a [Nabu Casa](https://www.nabucasa.com/) subscription) |
| **Google account** (optional) | Pushing the week's meals onto a shared Google calendar | A free Google Cloud project; [docs/GOOGLE-CALENDAR.md](docs/GOOGLE-CALENDAR.md) explains the two ways to sign in |
| **Google Chrome** (or another Chromium browser) | The recipe-import browser extension | [google.com/chrome](https://www.google.com/chrome/) |
| **Git** (optional) | Cloning/updating the project and keeping your recipes under version control | [git-scm.com](https://git-scm.com/) or `winget install Git.Git` |
| **Node.js 20+ (LTS)** (developers) | Running the extension's JavaScript unit tests only — *not* needed to run the app or use the extension | [nodejs.org](https://nodejs.org/) or `winget install OpenJS.NodeJS.LTS`; open a new terminal afterwards |
| **Docker** (developers) | Only the Raspberry Pi provisioning test (`pi/Dockerfile.provision-test`, `pi/test-in-docker.ps1`) | [docker.com](https://www.docker.com/products/docker-desktop/) |

Check what you have with:

```bash
python --version
node --version
```

### Chrome extension

The `chrome-extension/` directory holds an unpacked Chrome extension that extracts a recipe (via its page's Schema.org JSON-LD data) from any recipe website and sends it straight to your running local instance. To install it:

1. Go to `chrome://extensions`, enable Developer Mode.
2. Click "Load unpacked" and select the `chrome-extension/` directory.
3. With the app running and signed in at its address in the same Chrome profile, open any recipe page and click the extension icon. The extension assumes `http://127.0.0.1:5000`; if the app runs elsewhere (a Raspberry Pi, or via Tailscale), open the extension's **Settings** and enter that address.

## Running tests

The app and all of its Python modules (Python only):

```bash
python -m unittest discover -s tests
```

The Chrome extension's JSON-LD extraction logic (requires Node.js — see [Requirements](#requirements)):

```bash
node --test "chrome-extension/**/*.test.js"
```

## Project structure

| File/dir | Responsibility |
|---|---|
| `app.py` | Flask routes and templating |
| `recipe_sync.py` | Parses ORF YAML, syncs `recipes/*.yaml` into the database |
| `meal_master.py` | Converts legacy Meal Master (`.mmf`) recipes into ORF |
| `recipe_extraction.py` | Converts a Chrome-extension-extracted recipe into ORF |
| `unit_conversion.py` | Exact-arithmetic amount parsing, unit normalization, imperial conversion |
| `meal_calendar.py`, `pantry.py`, `shopping_list.py` | Calendar, pantry, and shopping-list logic |
| `voice.py` | Turns what Alexa heard into the app's values, matches spoken recipe names, and words the replies |
| `set_ha_link.py`, `ha_sync.py` | Links the app to a Home Assistant to-do list and keeps the shopping list mirrored both ways |
| `ha/` | Home Assistant YAML (rest_command + automation) and setup steps for the shopping-list mirror |
| `alexa/` | The Alexa skill: its interaction model, the Alexa-hosted forwarder code (`lambda_function.py` + `config.py` template) that adds the Home Assistant token, the Home Assistant YAML that bridges it, and setup steps |
| `install.ps1` | Windows entry point: sets up Python and the app's environment, runs `configure.py`, then offers to start the app and add it to Windows Startup |
| `configure.py` | The guided setup: asks the questions and saves the settings; run again to change them |
| `wizard_io.py` | The console the wizard talks through (prompts, menus, hidden password entry) |
| `wizard_steps.py` | The wizard's questions: password, who can reach the app, and each optional feature |
| `wizard_next.py` | Builds the "what's left to do" checklist, with your values filled into the Home Assistant and Alexa YAML |
| `wizard_pi.py` | The Raspberry Pi path: names the Pi, walks through Raspberry Pi Imager, writes the card, waits for the Pi |
| `set_password.py`, `set_api_token.py`, `set_ha_link.py`, `set_gcal.py`, `set_gcal_oauth.py` | The manual route for each setting the wizard handles; see the feature docs |
| `pi/` | The Pi: SD-card kit (`prepare-sd.ps1`, `boot/`), `install.sh`, `update.sh`, systemd units, and tests |
| `db.py` | SQLite schema and migrations |
| `recipes/` | Your recipe library — one YAML file per recipe |
| `templates/`, `static/` | Jinja2 templates and CSS/assets |
| `chrome-extension/` | The companion recipe-import Chrome extension |
| `tests/` | Unit and route tests |

## License

Meal Planner is released under the [MIT License](LICENSE).

The bundled fonts in `static/fonts/` are not covered by it: Barlow Condensed and IBM Plex Mono are licensed under the SIL Open Font License 1.1 ([OFL-BarlowCondensed.txt](static/fonts/OFL-BarlowCondensed.txt), [OFL-IBMPlexMono.txt](static/fonts/OFL-IBMPlexMono.txt)).
