#!/usr/bin/env bash
# Update Meal Planner on the Pi to the latest code and restart it.
#
#   ./pi/update.sh
#
# Pulls the frozen `pi` branch, installs any new Python packages, restarts the service.
# Your recipes and settings live outside the repo and are untouched.
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE=meal-planner

cd "$APP_DIR"
# The Pi is frozen on the `pi` branch (docs/RASPBERRY-PI.md): master is the desktop app now.
echo "==> git pull (pi branch)"
git fetch -q origin +refs/heads/pi:refs/remotes/origin/pi
if [ "$(git rev-parse --abbrev-ref HEAD)" != "pi" ]; then
  git checkout -q -B pi origin/pi
  git branch -q --set-upstream-to=origin/pi pi
fi
git merge --ff-only -q origin/pi
echo "==> python packages"
"$APP_DIR/.venv/bin/pip" install -q -r requirements.txt
if command -v systemctl >/dev/null && systemctl list-unit-files "$SERVICE.service" >/dev/null 2>&1; then
  echo "==> restarting $SERVICE"
  sudo systemctl restart "$SERVICE"
  sleep 3
  systemctl is-active --quiet "$SERVICE" && echo "    running" || { sudo journalctl -u "$SERVICE" -n 20 --no-pager; exit 1; }
else
  echo "==> no service installed; restart app.py by hand"
fi
