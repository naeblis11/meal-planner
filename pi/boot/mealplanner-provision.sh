#!/usr/bin/env bash
# First-boot provisioning for Meal Planner on a Raspberry Pi.
#
# Installed by mealplanner-hook.sh and run once by systemd, as root, after
# the network is up. Clones the app, installs it as a service, optionally
# joins Tailscale, then scrubs the secrets from the card and marks itself
# done. Everything it prints also goes to /var/log/mealplanner-provision.log
# and to an attached screen.
set -uo pipefail

BOOT=/boot/firmware
[ -d "$BOOT" ] || BOOT=/boot
CONF="$BOOT/mealplanner.conf"
LOG=/var/log/mealplanner-provision.log
STAMP=/var/lib/mealplanner-provisioned

exec > >(tee -a "$LOG") 2>&1
echo "=== Meal Planner provisioning $(date -Is) ==="

say()  { printf '\n==> %s\n' "$*"; }
warn() { printf '!!  %s\n' "$*"; }
die()  { printf '\nFAILED: %s\n' "$*"; printf 'Fix it, then: sudo systemctl start mealplanner-provision\n'; exit 1; }

# ---- settings -------------------------------------------------------------
HOUSEHOLD_PASSWORD=""; TAILSCALE_AUTH_KEY=""
REPO_URL="https://github.com/naeblis11/meal-planner.git"; REPO_BRANCH="pi"
# shellcheck disable=SC1090
[ -f "$CONF" ] && . "$CONF"

# The account to install under: the first ordinary user on the system.
RUN_USER="$(getent passwd 1000 | cut -d: -f1)"
[ -n "$RUN_USER" ] || die "no user with uid 1000 -- set one up in Raspberry Pi Imager"
RUN_HOME="$(getent passwd "$RUN_USER" | cut -d: -f6)"
APP_DIR="$RUN_HOME/Meal_Planner"
SECRETS_CARD="$BOOT/mealplanner-secrets.env"
KEY_CARD="$BOOT/mealplanner-gcal-key.json"
echo "  user=$RUN_USER  home=$RUN_HOME  repo=$REPO_URL ($REPO_BRANCH)"

say "Waiting for the network"
for _ in $(seq 1 60); do
  getent hosts github.com >/dev/null 2>&1 && break
  sleep 2
done
if ! getent hosts github.com >/dev/null 2>&1; then
  echo "  what NetworkManager has:"
  nmcli -t -f NAME,DEVICE,STATE connection show 2>/dev/null | sed "s/^/    /"
  die "no network after two minutes -- check WIFI_SSID / WIFI_PASSWORD / WIFI_COUNTRY in mealplanner.conf (or the Wi-Fi you set in Imager). Fix the card, or plug in Ethernet, then: sudo systemctl start mealplanner-provision"
fi

say "System packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq || die "apt-get update failed"
apt-get install -y -qq git python3 python3-venv python3-pip libjpeg62-turbo avahi-daemon curl ca-certificates \
  || die "apt-get install failed"

say "Fetching the app into $APP_DIR"
if [ -d "$APP_DIR/.git" ]; then
  sudo -u "$RUN_USER" git -C "$APP_DIR" fetch --depth 1 origin "+refs/heads/$REPO_BRANCH:refs/remotes/origin/$REPO_BRANCH" \
    && sudo -u "$RUN_USER" git -C "$APP_DIR" reset --hard "origin/$REPO_BRANCH" || die "git update failed"
else
  sudo -u "$RUN_USER" git clone --depth 1 --branch "$REPO_BRANCH" "$REPO_URL" "$APP_DIR" || die "git clone failed"
fi

say "App settings from the installer"
ENV_FILE="$(cd "$APP_DIR" && sudo -u "$RUN_USER" -H python3 -c 'import paths; print(paths.env_path())')" \
  || die "could not work out where the settings file goes"
if [ -f "$SECRETS_CARD" ]; then
  install -d -m 700 -o "$RUN_USER" -g "$RUN_USER" "$(dirname "$ENV_FILE")"
  install -m 600 -o "$RUN_USER" -g "$RUN_USER" "$SECRETS_CARD" "$ENV_FILE" \
    || die "could not install the settings file"
  # It has served its purpose; do not leave the hash on the card if a later step fails.
  rm -f "$SECRETS_CARD"
  echo "  installed $ENV_FILE"
else
  echo "  none on the card"
fi

if [ -f "$KEY_CARD" ]; then
  say "Google Calendar key"
  DATA_DIR="$(cd "$APP_DIR" && sudo -u "$RUN_USER" -H python3 -c 'import paths; print(paths.data_dir())')" \
    || die "could not work out where the data folder is"
  [ -n "$DATA_DIR" ] || die "the data folder path came back empty"
  install -d -o "$RUN_USER" -g "$RUN_USER" "$DATA_DIR" || die "could not create $DATA_DIR"
  install -m 600 -o "$RUN_USER" -g "$RUN_USER" "$KEY_CARD" "$DATA_DIR/google-calendar-key.json" \
    || die "could not install the Google Calendar key"
  rm -f "$KEY_CARD"
  install -d -m 700 -o "$RUN_USER" -g "$RUN_USER" "$(dirname "$ENV_FILE")"
  [ -f "$ENV_FILE" ] || install -m 600 -o "$RUN_USER" -g "$RUN_USER" /dev/null "$ENV_FILE"
  sed -i '/^MEAL_PLANNER_GCAL_CREDENTIALS=/d' "$ENV_FILE"
  echo "MEAL_PLANNER_GCAL_CREDENTIALS=$DATA_DIR/google-calendar-key.json" >> "$ENV_FILE"
  echo "  installed (install.sh below starts the service with it)"
fi

say "Installing the app and its service"
# install.sh does the venv, packages, password, systemd unit and enable.
if [ -n "$HOUSEHOLD_PASSWORD" ]; then
  # Blank it on the card now, before install.sh gets a chance to fail.
  [ -f "$CONF" ] && sed -i 's/^HOUSEHOLD_PASSWORD=.*/HOUSEHOLD_PASSWORD=/' "$CONF"
  sudo -u "$RUN_USER" MEAL_PLANNER_SETUP_PASSWORD="$HOUSEHOLD_PASSWORD" bash "$APP_DIR/pi/install.sh" \
    || die "pi/install.sh failed -- see above"
elif grep -qs '^MEAL_PLANNER_PASSWORD_HASH=' "$ENV_FILE"; then
  # The password hash is already in place (from the card, or an earlier attempt), so install.sh skips the password step.
  sudo -u "$RUN_USER" bash "$APP_DIR/pi/install.sh" < /dev/null || die "pi/install.sh failed -- see above"
else
  warn "No HOUSEHOLD_PASSWORD in mealplanner.conf."
  warn "The app cannot start until one is set. After this finishes, run:"
  warn "  ssh $RUN_USER@$(hostname).local"
  warn "  cd Meal_Planner && .venv/bin/python set_password.py && sudo systemctl restart meal-planner"
  sudo -u "$RUN_USER" MEAL_PLANNER_SKIP_SERVICE_START=1 bash "$APP_DIR/pi/install.sh" \
    || warn "pi/install.sh reported a problem; the password is probably why"
fi

say "Tailscale"
# One script for every route in: first boot with a key, a Pi where Tailscale
# was installed by hand later, and every reboot after that. Provisioning runs
# once; mealplanner-tailscale.service re-runs this at boot.
if ! bash "$APP_DIR/pi/setup-tailscale.sh" "$TAILSCALE_AUTH_KEY"; then
  warn "Tailscale setup had a problem. Re-run it later with:"
  warn "  sudo $APP_DIR/pi/setup-tailscale.sh"
fi

say "Clearing the secrets off the SD card"
rm -f "$SECRETS_CARD" "$KEY_CARD"
if [ -f "$CONF" ]; then
  sed -i -e 's/^HOUSEHOLD_PASSWORD=.*/HOUSEHOLD_PASSWORD=/' \
         -e 's/^TAILSCALE_AUTH_KEY=.*/TAILSCALE_AUTH_KEY=/' \
         -e 's/^WIFI_PASSWORD=.*/WIFI_PASSWORD=/' "$CONF" \
    && echo "  done (the values were used and removed)"
fi

mkdir -p "$(dirname "$STAMP")" && date -Is > "$STAMP"
systemctl disable mealplanner-provision.service >/dev/null 2>&1 || true

say "Finished"
IP="$(hostname -I | awk '{print $1}')"
cat <<EOF
  The app is at   http://$(hostname).local:5000   (or http://$IP:5000)
  Recipes live in $RUN_HOME/meal-planner/recipes
  Service:        sudo systemctl status meal-planner
  This log:       $LOG
EOF
