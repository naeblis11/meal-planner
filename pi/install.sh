#!/usr/bin/env bash
# Install Meal Planner on a Raspberry Pi (Raspberry Pi OS / Debian).
#
#   git clone https://github.com/naeblis11/meal-planner.git Meal_Planner
#   cd Meal_Planner
#   ./pi/install.sh
#
# Same code as the Windows install; only where files live differs:
#   secrets   ~/.config/meal-planner/.env
#   recipes   ~/meal-planner/
# Re-running is safe: it updates packages and the service and keeps your data.
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE=meal-planner
# Deliberately not $SUDO_USER: this script also runs via
# `sudo -u <user> bash pi/install.sh` from mealplanner-provision.sh (which
# itself runs as root), and sudo always sets SUDO_USER to the *invoking*
# user (root) regardless of -u's target -- picking it up here would install
# the systemd service as root, not the account the app's data actually
# lives under. id -un is always correct: line below already refuses to run
# as literal root, so this is never actually root in any real invocation.
RUN_USER="$(id -un)"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die()  { printf '\n\033[31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] && die "Run this as your normal user, not with sudo; it asks for sudo where needed."
command -v python3 >/dev/null || die "python3 is missing. On Raspberry Pi OS: sudo apt install python3"
PYVER="$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' \
  || die "Python $PYVER found; 3.11 or newer is needed (Raspberry Pi OS Bookworm ships 3.11)."

step "System packages (python venv, JPEG library for photos)"
if command -v apt-get >/dev/null; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq python3-venv python3-pip libjpeg62-turbo >/dev/null \
    || sudo apt-get install -y -qq python3-venv python3-pip libjpeg-turbo8 >/dev/null \
    || die "apt-get could not install python3-venv / libjpeg"
else
  echo "(no apt-get -- assuming python3-venv and libjpeg are present)"
fi

step "Python environment in $APP_DIR/.venv"
[ -d "$APP_DIR/.venv" ] || python3 -m venv "$APP_DIR/.venv" || die "could not create the virtualenv"
"$APP_DIR/.venv/bin/pip" install -q --upgrade pip
"$APP_DIR/.venv/bin/pip" install -q -r "$APP_DIR/requirements.txt" || die "pip install failed"

step "Checking the app imports on this machine"
(cd "$APP_DIR" && "$APP_DIR/.venv/bin/python" -c 'import app, paths; print("  data folder:", paths.data_dir()); print("  secrets:   ", paths.env_path())') \
  || die "the app does not import -- see the error above"

step "Household password"
ENV_FILE="$(cd "$APP_DIR" && "$APP_DIR/.venv/bin/python" -c 'import paths; print(paths.env_path())')"
mkdir -p "$(dirname "$ENV_FILE")"
touch "$ENV_FILE"
if [ -f "$ENV_FILE" ] && grep -q '^MEAL_PLANNER_PASSWORD_HASH=' "$ENV_FILE"; then
  echo "  already set in $ENV_FILE (run '$APP_DIR/.venv/bin/python configure.py' to change it)"
else
  if [ -n "${MEAL_PLANNER_SETUP_PASSWORD:-}" ]; then
    (cd "$APP_DIR" && "$APP_DIR/.venv/bin/python" set_password.py) || die "set_password.py did not complete"
  elif [ -n "${MEAL_PLANNER_SKIP_SERVICE_START:-}" ]; then
    echo "  skipped (no password given; set one later with configure.py)"
  elif [ -t 0 ]; then
    (cd "$APP_DIR" && "$APP_DIR/.venv/bin/python" configure.py --no-restart) || die "configure.py did not complete"
  else
    (cd "$APP_DIR" && "$APP_DIR/.venv/bin/python" set_password.py) || die "set_password.py did not complete"
  fi
fi
# A Pi is a server: reachable from the household's phones by default.
grep -q '^MEAL_PLANNER_HOST=' "$ENV_FILE" || echo 'MEAL_PLANNER_HOST=0.0.0.0' >> "$ENV_FILE"

step "systemd service ($SERVICE)"
if command -v systemctl >/dev/null; then
  sed -e "s|__USER__|$RUN_USER|g" -e "s|__APP_DIR__|$APP_DIR|g" "$APP_DIR/pi/$SERVICE.service" \
    | sudo tee "/etc/systemd/system/$SERVICE.service" >/dev/null
  sudo systemctl daemon-reload
  sudo systemctl enable "$SERVICE" >/dev/null
  if [ -n "${MEAL_PLANNER_SKIP_SERVICE_START:-}" ] && ! grep -q '^MEAL_PLANNER_PASSWORD_HASH=' "$ENV_FILE"; then
    echo "  enabled for boot, not started (no household password yet)"
  else
    sudo systemctl restart "$SERVICE"
    sleep 3
    if systemctl is-active --quiet "$SERVICE"; then
      echo "  running"
    else
      sudo journalctl -u "$SERVICE" -n 20 --no-pager
      die "the service did not stay up -- log above"
    fi
  fi
else
  echo "  (no systemd here; start the app with: $APP_DIR/.venv/bin/python app.py)"
fi

step "Tailscale hook (for reaching the app away from home)"
if command -v systemctl >/dev/null; then
  sed -e "s|__APP_DIR__|$APP_DIR|g" "$APP_DIR/pi/mealplanner-tailscale.service" \
    | sudo tee /etc/systemd/system/mealplanner-tailscale.service >/dev/null
  sudo systemctl daemon-reload
  sudo systemctl enable mealplanner-tailscale >/dev/null
  echo "  installed; it publishes this Pi to your tailnet at every boot"
  echo "  (nothing happens until Tailscale is set up: sudo $APP_DIR/pi/setup-tailscale.sh)"
else
  echo "  (no systemd here; skipped)"
fi

step "Done"
HOST="$(hostname)"
cat <<EOF
  On this network:   http://$HOST.local:5000
  Recipes live in:   $HOME/meal-planner/recipes
  Logs:              journalctl -u $SERVICE -f
  Update later:      $APP_DIR/pi/update.sh

Point the Chrome extension at http://$HOST.local:5000 (its Settings page), and
if you use Home Assistant, the rest_command URLs in ha/shopping-list.yaml and alexa/home-assistant.yaml must use $HOST.local.
EOF
