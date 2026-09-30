#!/usr/bin/env bash
# Publish the Meal Planner to your tailnet, and tell the app it is behind a
# proxy. Run it whenever you like:
#
#   sudo ./pi/setup-tailscale.sh                 # Tailscale already set up
#   sudo ./pi/setup-tailscale.sh tskey-auth-...  # join the tailnet too
#
# Safe to re-run: it only changes what is not already right.
#
# This is deliberately NOT part of first-boot provisioning. That runs exactly
# once, so a Pi where Tailscale was installed afterwards -- by hand, or with a
# key that had expired -- would never get published, and the app would never
# learn to trust the Serve proxy. mealplanner-tailscale.service runs this at
# every boot instead, which covers both cases.
set -uo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT=5000
AUTH_KEY="${1:-${TAILSCALE_AUTH_KEY:-}}"

say()  { printf '==> %s\n' "$*"; }
warn() { printf '!!  %s\n' "$*"; }

running() { tailscale status --json 2>/dev/null | grep -q '"BackendState"[[:space:]]*:[[:space:]]*"Running"'; }

[ "$(id -u)" -eq 0 ] || { warn "run this with sudo: sudo $0"; exit 1; }

if ! command -v tailscale >/dev/null; then
  if [ -z "$AUTH_KEY" ]; then
    say "Tailscale is not installed -- nothing to do."
    say "To use it: curl -fsSL https://tailscale.com/install.sh | sh, then run this again."
    exit 0
  fi
  say "Installing Tailscale"
  curl -fsSL https://tailscale.com/install.sh | sh \
    || { warn "Tailscale install failed -- install it by hand and re-run this"; exit 0; }
fi

if [ -n "$AUTH_KEY" ]; then
  if running; then
    say "Already signed in to the tailnet; ignoring the auth key"
  else
    say "Joining the tailnet"
    # --ssh is what lets you get a shell on the Pi from another of your own
    # machines without a password or an exposed port.
    tailscale up --authkey="$AUTH_KEY" --hostname="$(hostname)" --ssh \
      || warn "tailscale up failed -- the key may be expired or already used"
  fi
else
  # At boot, tailscaled is often still connecting when this unit starts.
  for _ in $(seq 1 30); do
    running && break
    sleep 2
  done
fi

if ! running; then
  say "Not signed in to a tailnet yet. Run 'sudo tailscale up --ssh', then this script again."
  exit 0
fi

# --- publish to the tailnet (never Funnel: that would be the public internet)
if tailscale serve status 2>/dev/null | grep -q "127.0.0.1:$PORT"; then
  say "Already published to the tailnet"
else
  say "Publishing to the tailnet over HTTPS"
  tailscale serve --bg --https=443 "http://127.0.0.1:$PORT" \
    || warn "tailscale serve failed -- try it by hand to see why"
fi

# --- the app has to trust the proxy Serve puts in front of it
# Without this the session cookie is not marked Secure and logging in over
# HTTPS from a phone fails in a way that looks like a wrong password.
RUN_USER="$(stat -c %U "$APP_DIR" 2>/dev/null)"
ENV_FILE=""
[ -n "$RUN_USER" ] && ENV_FILE="$(sudo -u "$RUN_USER" sh -c "cd '$APP_DIR' && .venv/bin/python -c 'import paths; print(paths.env_path())'" 2>/dev/null)"
if [ -z "$ENV_FILE" ]; then
  warn "could not work out where the secrets file is -- set MEAL_PLANNER_BEHIND_PROXY=1 in it by hand"
elif grep -q '^MEAL_PLANNER_BEHIND_PROXY=' "$ENV_FILE" 2>/dev/null; then
  say "The app already trusts the Serve proxy"
else
  printf '\nMEAL_PLANNER_BEHIND_PROXY=1\n' >> "$ENV_FILE"
  chown "$RUN_USER" "$ENV_FILE" 2>/dev/null || true
  say "Told the app to trust the Serve proxy; restarting it"
  systemctl restart meal-planner 2>/dev/null || true
fi

DNS="$(tailscale status --json 2>/dev/null | sed -n 's/.*"DNSName"[[:space:]]*:[[:space:]]*"\([^"]*\)\.".*/\1/p' | head -1)"
[ -n "$DNS" ] && say "Reachable from your phone at https://$DNS/"
exit 0
