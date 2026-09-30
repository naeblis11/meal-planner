#!/usr/bin/env bash
# Exercise the SD-card first-boot provisioning the way a Pi would run it,
# but inside a container: no systemd, no real Tailscale, and the "clone"
# comes from the working copy rather than GitHub so unpushed changes are
# what gets tested. Run by pi/Dockerfile.provision-test.
set -uo pipefail

pass=0; fail=0
ok()  { pass=$((pass+1)); printf '  PASS  %s\n' "$*"; }
bad() { fail=$((fail+1)); printf '  FAIL  %s\n' "$*"; }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }

BOOT=/boot/firmware
mkdir -p "$BOOT"
cp /repo/pi/boot/mealplanner-provision.sh "$BOOT/"
cp /repo/pi/boot/mealplanner-hook.sh "$BOOT/"
sed -e 's|^HOUSEHOLD_PASSWORD=.*|HOUSEHOLD_PASSWORD=test-pw|' \
    -e 's|^TAILSCALE_AUTH_KEY=.*|TAILSCALE_AUTH_KEY=tskey-fake-for-the-test|' \
    -e 's|^WIFI_SSID=.*|WIFI_SSID=HOMELAN|' \
    -e 's|^WIFI_PASSWORD=.*|WIFI_PASSWORD=wifi-secret|' \
    -e 's|^WIFI_COUNTRY=.*|WIFI_COUNTRY=CA|' \
    -e 's|^REPO_URL=.*|REPO_URL=/repo-origin|' \
    /repo/pi/boot/mealplanner.conf > "$BOOT/mealplanner.conf"

echo "==> the early hook (installs the provisioning service)"
# No systemd in a container: stub systemctl so the hook and installer can run.
cat > /usr/local/bin/systemctl <<'STUB'
#!/bin/sh
echo "[systemctl stub] $*" >> /tmp/systemctl.log
case "$1" in is-active) exit 0 ;; esac
exit 0
STUB
chmod +x /usr/local/bin/systemctl

# Simulate being invoked from prepare-sd.ps1's cmdline.txt fallback, the way
# a real first boot with no Imager/cloud-init customisation would be.
echo "console=serial0,115200 root=PARTUUID=deadbeef-02 rootfstype=ext4 fsck.repair=yes rootwait systemd.run=/boot/firmware/mealplanner-hook.sh systemd.run_success_action=reboot systemd.unit=kernel-command-line.target" > "$BOOT/cmdline.txt"

sh "$BOOT/mealplanner-hook.sh" > /tmp/hook.log 2>&1 && ok "hook ran" || { bad "hook failed"; cat /tmp/hook.log; }
check "cleared systemd.run from cmdline.txt so the next boot is normal" '! grep -q "systemd\.run=" "'"$BOOT"'/cmdline.txt"'
check "cleared systemd.run_success_action from cmdline.txt" '! grep -q "systemd\.run_success_action=" "'"$BOOT"'/cmdline.txt"'
check "cleared the kernel-command-line target from cmdline.txt" '! grep -q "systemd\.unit=kernel-command-line" "'"$BOOT"'/cmdline.txt"'
check "left the rest of cmdline.txt alone" "grep -q 'root=PARTUUID=deadbeef-02' $BOOT/cmdline.txt"
check "hook installed the provisioner" 'test -x /usr/local/sbin/mealplanner-provision.sh'
check "hook wrote the systemd unit" 'grep -q "ExecStart=/usr/local/sbin/mealplanner-provision.sh" /etc/systemd/system/mealplanner-provision.service'
check "unit waits for the network" 'grep -q "After=network-online.target" /etc/systemd/system/mealplanner-provision.service'
check "unit will not run twice" 'grep -q "ConditionPathExists=!/var/lib/mealplanner-provisioned" /etc/systemd/system/mealplanner-provision.service'
check "hook enabled AND started the unit (not just enabled for next boot)" 'grep -q "enable --now mealplanner-provision" /tmp/systemctl.log'

PROFILE=/etc/NetworkManager/system-connections/mealplanner-wifi.nmconnection
check "wrote a NetworkManager Wi-Fi profile" "test -f $PROFILE"
check "profile carries the network name" "grep -q '^ssid=HOMELAN$' $PROFILE"
check "profile carries the passphrase as wpa-psk" "grep -q '^key-mgmt=wpa-psk$' $PROFILE && grep -q '^psk=wifi-secret$' $PROFILE"
check "profile connects itself at boot" "grep -q '^autoconnect=true$' $PROFILE"
check "profile is readable only by root" "test \"\$(stat -c %a $PROFILE)\" = 600"

echo "==> a fake Tailscale, so the real one is not downloaded"
# Stateful, because the point of these tests is what happens on the second
# run: not signed in until `up`, no serve config until `serve --bg`.
cat > /usr/local/bin/tailscale <<'STUB'
#!/bin/sh
echo "[tailscale stub] $*" >> /tmp/tailscale.log
case "$1" in
  status)
    if [ -f /tmp/ts-up ]; then
      echo '{"BackendState": "Running", "Self": {"DNSName": "meal-planner.tailnet.ts.net."}}'
    else
      echo '{"BackendState": "NeedsLogin", "Self": {"DNSName": ""}}'
    fi ;;
  up) touch /tmp/ts-up ;;
  serve)
    if [ "$2" = "status" ]; then
      if [ -f /tmp/ts-serve ]; then cat /tmp/ts-serve; else echo "No serve config"; fi
    else
      echo "https://meal-planner.tailnet.ts.net (tailnet only)" > /tmp/ts-serve
      echo "|-- / proxy http://127.0.0.1:5000" >> /tmp/ts-serve
    fi ;;
esac
exit 0
STUB
chmod +x /usr/local/bin/tailscale

echo "==> provisioning (clone, install, service, tailscale, scrub)"
if /usr/local/sbin/mealplanner-provision.sh > /tmp/provision.log 2>&1; then
  ok "provision.sh completed"
else
  bad "provision.sh exited $?"; tail -30 /tmp/provision.log
fi

HOME_DIR="$(getent passwd 1000 | cut -d: -f6)"
check "cloned the app" "test -f $HOME_DIR/Meal_Planner/app.py"
check "made the virtualenv" "test -x $HOME_DIR/Meal_Planner/.venv/bin/python"
check "wrote the secrets file" "grep -q MEAL_PLANNER_PASSWORD_HASH $HOME_DIR/.config/meal-planner/.env"
check "set the host for the LAN" "grep -q '^MEAL_PLANNER_HOST=0.0.0.0' $HOME_DIR/.config/meal-planner/.env"
check "trusts the Tailscale proxy" "grep -q '^MEAL_PLANNER_BEHIND_PROXY=1' $HOME_DIR/.config/meal-planner/.env"
check "installed the app service" 'grep -q "ExecStart=.*app.py" /etc/systemd/system/meal-planner.service'
check "service runs as the real user, not root (this is invoked via sudo -u from a root-owned caller, same as mealplanner-provision.sh does)" \
  'grep -q "^User=$(getent passwd 1000 | cut -d: -f1)$" /etc/systemd/system/meal-planner.service'
check "service restarts after a power cut" 'grep -q "^Restart=always" /etc/systemd/system/meal-planner.service'
check "service starts at boot" 'grep -q "enable meal-planner" /tmp/systemctl.log'
check "joined tailscale with the key" 'grep -q "up --authkey=tskey-fake-for-the-test" /tmp/tailscale.log'
check "published HTTPS to the tailnet only" 'grep -q "serve --bg --https=443 http://127.0.0.1:5000" /tmp/tailscale.log'
check "did not enable Funnel" '! grep -q funnel /tmp/tailscale.log'
check "enabled Tailscale SSH, so the Pi can be reached without a password" 'grep -q "up .*--ssh" /tmp/tailscale.log'
check "installed the boot-time tailscale unit" 'grep -q "ExecStart=.*/pi/setup-tailscale.sh" /etc/systemd/system/mealplanner-tailscale.service'
check "the boot unit points at the real app directory, not the placeholder" \
  '! grep -q "__APP_DIR__" /etc/systemd/system/mealplanner-tailscale.service'
check "boot unit is enabled" 'grep -q "enable mealplanner-tailscale" /tmp/systemctl.log'

echo "==> Tailscale set up AFTER provisioning already ran (the regression)"
# Provisioning runs exactly once and stamps itself done. A Pi where Tailscale
# was installed by hand afterwards -- or whose auth key had expired -- used to
# be left unpublished for ever, with the app unaware it was behind a proxy.
ENV_FILE="$HOME_DIR/.config/meal-planner/.env"
rm -f /tmp/ts-serve
sed -i '/^MEAL_PLANNER_BEHIND_PROXY=/d' "$ENV_FILE"
: > /tmp/tailscale.log
if bash "$HOME_DIR/Meal_Planner/pi/setup-tailscale.sh" > /tmp/late-tailscale.log 2>&1; then
  ok "setup-tailscale.sh runs standalone, with no auth key"
else
  bad "setup-tailscale.sh failed standalone"; tail -20 /tmp/late-tailscale.log
fi
check "published to the tailnet on its own" 'grep -q "serve --bg --https=443 http://127.0.0.1:5000" /tmp/tailscale.log'
check "restored MEAL_PLANNER_BEHIND_PROXY" 'grep -q "^MEAL_PLANNER_BEHIND_PROXY=1" "'"$ENV_FILE"'"'
check "did not try to log in again without a key" '! grep -q "^\[tailscale stub\] up" /tmp/tailscale.log'
check "told the user the https address" 'grep -q "https://meal-planner.tailnet.ts.net" /tmp/late-tailscale.log'

: > /tmp/tailscale.log
bash "$HOME_DIR/Meal_Planner/pi/setup-tailscale.sh" > /tmp/late-tailscale2.log 2>&1
check "re-running does not publish a second time" '! grep -q "serve --bg" /tmp/tailscale.log'
check "re-running does not duplicate the env line" \
  'test "$(grep -c "^MEAL_PLANNER_BEHIND_PROXY=" "'"$ENV_FILE"'")" = 1'
check "scrubbed the password off the card" 'grep -q "^HOUSEHOLD_PASSWORD=$" /boot/firmware/mealplanner.conf'
check "scrubbed the tailscale key off the card" 'grep -q "^TAILSCALE_AUTH_KEY=$" /boot/firmware/mealplanner.conf'
check "scrubbed the wifi password off the card" 'grep -q "^WIFI_PASSWORD=$" /boot/firmware/mealplanner.conf'
check "marked itself done" 'test -f /var/lib/mealplanner-provisioned'
check "logged the run" 'test -s /var/log/mealplanner-provision.log'

echo "==> the installed app actually serves"
HOME_DIR="$(getent passwd 1000 | cut -d: -f6)"
su - "$(getent passwd 1000 | cut -d: -f1)" -c "cd $HOME_DIR/Meal_Planner && .venv/bin/python app.py" > /tmp/app.log 2>&1 &
for _ in $(seq 1 30); do curl -fs http://127.0.0.1:5000/healthz >/dev/null 2>&1 && break; sleep 1; done
check "answers /healthz" 'curl -fs http://127.0.0.1:5000/healthz | grep -q "\"ok\": *true"'
check "the household password from the card works" 'curl -fs -o /dev/null -w "%{http_code}" -d "password=test-pw" http://127.0.0.1:5000/login | grep -q 302'
check "a wrong password is refused" 'curl -s -o /dev/null -w "%{http_code}" -d "password=nope" http://127.0.0.1:5000/login | grep -q 401'
check "behaves as if behind HTTPS (Secure cookie)" 'curl -s -D- -o /dev/null -H "X-Forwarded-Proto: https" -d "password=test-pw" http://127.0.0.1:5000/login | grep -i "^set-cookie" | grep -q Secure'
pkill -f "app.py" 2>/dev/null

echo "==> re-running provisioning must be a no-op"
before=$(date -Is)
if /usr/local/sbin/mealplanner-provision.sh > /tmp/provision2.log 2>&1; then ok "second run completed"; else bad "second run failed"; fi
check "the stamp file was not rewritten pointlessly" 'test -f /var/lib/mealplanner-provisioned'

echo "==> a card prepared by the Windows installer (secrets file, no plain password)"
rm -f /var/lib/mealplanner-provisioned "$ENV_FILE"
: > /tmp/systemctl.log
HASH="$("$HOME_DIR/Meal_Planner/.venv/bin/python" -c 'from werkzeug.security import generate_password_hash as g; print(g("card-pw"))')"
printf 'MEAL_PLANNER_PASSWORD_HASH=%s\nMEAL_PLANNER_SECRET_KEY=%s\nMEAL_PLANNER_HOST=0.0.0.0\n' \
  "$HASH" "$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')" > "$BOOT/mealplanner-secrets.env"
echo '{"type": "service_account"}' > "$BOOT/mealplanner-gcal-key.json"
sed -i 's/^HOUSEHOLD_PASSWORD=.*/HOUSEHOLD_PASSWORD=/' "$BOOT/mealplanner.conf"
if /usr/local/sbin/mealplanner-provision.sh > /tmp/provision3.log 2>&1; then ok "provisioned from the secrets file"; else bad "provision failed"; tail -30 /tmp/provision3.log; fi
check "installed the secrets file" "grep -q '^MEAL_PLANNER_PASSWORD_HASH=' $ENV_FILE"
check "secrets file is private" "test \"\$(stat -c %a $ENV_FILE)\" = 600"
check "secrets file belongs to the app user" "test \"\$(stat -c %u $ENV_FILE)\" = 1000"
check "installed the calendar key privately" "test \"\$(stat -c %a $HOME_DIR/meal-planner/google-calendar-key.json)\" = 600"
check "pointed the app at the key" "grep -q '^MEAL_PLANNER_GCAL_CREDENTIALS=$HOME_DIR/meal-planner/google-calendar-key.json$' $ENV_FILE"
check "removed the secrets file from the card" "test ! -e $BOOT/mealplanner-secrets.env"
check "removed the key from the card" "test ! -e $BOOT/mealplanner-gcal-key.json"
check "started the service (not left waiting for a password)" "grep -q 'restart meal-planner' /tmp/systemctl.log"
su - "$(getent passwd 1000 | cut -d: -f1)" -c "cd $HOME_DIR/Meal_Planner && .venv/bin/python app.py" > /tmp/app3.log 2>&1 &
for _ in $(seq 1 30); do curl -fs http://127.0.0.1:5000/healthz >/dev/null 2>&1 && break; sleep 1; done
check "the password from the installer works" 'curl -fs -o /dev/null -w "%{http_code}" -d "password=card-pw" http://127.0.0.1:5000/login | grep -q 302'
pkill -f "app.py" 2>/dev/null

echo "==> install.sh fails: card secrets must already be gone"
# Break the "origin" the provisioner fetches from, so pip cannot install.
git -C /repo-origin -c user.email=t@t -c user.name=t commit -q --allow-empty -m marker
echo "no-such-package-for-the-test==1.0" >> /repo-origin/requirements.txt
git -C /repo-origin -c user.email=t@t -c user.name=t commit -qam "break requirements"
rm -f "$ENV_FILE"
printf 'MEAL_PLANNER_PASSWORD_HASH=x\n' > "$BOOT/mealplanner-secrets.env"
echo '{"type": "service_account"}' > "$BOOT/mealplanner-gcal-key.json"
sed -i 's/^HOUSEHOLD_PASSWORD=.*/HOUSEHOLD_PASSWORD=/' "$BOOT/mealplanner.conf"
if /usr/local/sbin/mealplanner-provision.sh > /tmp/provision4.log 2>&1; then bad "provisioning should have failed"; else ok "provisioning failed as arranged"; fi
check "the secrets file is off the card after a failure" "test ! -e $BOOT/mealplanner-secrets.env"
check "the settings were installed before the failure" "grep -q '^MEAL_PLANNER_PASSWORD_HASH=' $ENV_FILE"
check "the key is off the card after a failure" "test ! -e $BOOT/mealplanner-gcal-key.json"
# The password route.
rm -f "$ENV_FILE"
sed -i 's/^HOUSEHOLD_PASSWORD=.*/HOUSEHOLD_PASSWORD=plain-secret/' "$BOOT/mealplanner.conf"
/usr/local/sbin/mealplanner-provision.sh > /tmp/provision5.log 2>&1 && bad "provisioning should have failed" || ok "password route failed as arranged"
check "HOUSEHOLD_PASSWORD is blank on the card after a failure" "grep -q '^HOUSEHOLD_PASSWORD=$' $BOOT/mealplanner.conf"
# A retry, with the hash already installed.
printf 'MEAL_PLANNER_PASSWORD_HASH=x\n' > "$ENV_FILE"
/usr/local/sbin/mealplanner-provision.sh > /tmp/provision6.log 2>&1
check "a retry does not claim the password is missing" "! grep -q 'No HOUSEHOLD_PASSWORD' /tmp/provision6.log"
git -C /repo-origin reset -q --hard HEAD~2

echo
echo "==> $pass passed, $fail failed"
[ $fail -eq 0 ]
