#!/bin/sh
# Runs once, very early in the Pi's first boot (before the network is up),
# either from Raspberry Pi Imager's firstrun.sh or straight from
# cmdline.txt. All it does is arrange for the real provisioning to run
# later, once there is a network. Kept tiny and dependency-free on purpose.
set -e

BOOT=/boot/firmware
[ -d "$BOOT" ] || BOOT=/boot

# When there's no Imager/cloud-init customisation to hook into, prepare-sd.ps1
# falls back to invoking this script straight from the kernel command line
# (systemd.run=..., with systemd.run_success_action=reboot on success). That
# kernel parameter persists across reboots on its own, so if we don't remove
# it here, every subsequent boot repeats this exact same rescue-mode run
# forever instead of ever reaching a normal boot. Safe to always attempt:
# a no-op when this was invoked via firstrun.sh or cloud-init instead.
CMDLINE="$BOOT/cmdline.txt"
if [ -f "$CMDLINE" ] && grep -q 'systemd\.run=' "$CMDLINE"; then
  sed -i -E \
    -e 's/ ?systemd\.run=[^ ]*//g' \
    -e 's/ ?systemd\.run_success_action=[^ ]*//g' \
    -e 's/ ?systemd\.unit=kernel-command-line\.target//g' \
    "$CMDLINE" || true
fi

install -m 755 "$BOOT/mealplanner-provision.sh" /usr/local/sbin/mealplanner-provision.sh

# Wi-Fi first: on a Pi with no Ethernet, nothing else can happen without it.
# Raspberry Pi OS Bookworm uses NetworkManager, so a keyfile in
# system-connections is all it takes -- the old boot-partition
# wpa_supplicant.conf is no longer read.
WIFI_SSID=""; WIFI_PASSWORD=""; WIFI_COUNTRY="US"
# shellcheck disable=SC1090
[ -f "$BOOT/mealplanner.conf" ] && . "$BOOT/mealplanner.conf"
if [ -n "$WIFI_SSID" ]; then
  mkdir -p /etc/NetworkManager/system-connections
  PROFILE=/etc/NetworkManager/system-connections/mealplanner-wifi.nmconnection
  {
    echo "[connection]"
    echo "id=mealplanner-wifi"
    echo "type=wifi"
    echo "interface-name=wlan0"
    echo "autoconnect=true"
    echo "autoconnect-retries=0"
    echo ""
    echo "[wifi]"
    echo "mode=infrastructure"
    echo "ssid=$WIFI_SSID"
    echo ""
    if [ -n "$WIFI_PASSWORD" ]; then
      echo "[wifi-security]"
      echo "key-mgmt=wpa-psk"
      echo "psk=$WIFI_PASSWORD"
      echo ""
    fi
    echo "[ipv4]"
    echo "method=auto"
    echo ""
    echo "[ipv6]"
    echo "method=auto"
  } > "$PROFILE"
  chmod 600 "$PROFILE"
  chown root:root "$PROFILE"
  # The radio stays blocked until the regulatory country is set.
  command -v raspi-config >/dev/null && raspi-config nonint do_wifi_country "$WIFI_COUNTRY" || true
  command -v rfkill >/dev/null && rfkill unblock wifi || true
fi

cat > /etc/systemd/system/mealplanner-provision.service <<'UNIT'
[Unit]
Description=First-boot provisioning for Meal Planner
After=network-online.target
Wants=network-online.target
ConditionPathExists=!/var/lib/mealplanner-provisioned

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/local/sbin/mealplanner-provision.sh
# Show progress on an attached screen as well as in the journal.
StandardOutput=journal+console
StandardError=journal+console
TimeoutStartSec=3600

[Install]
WantedBy=multi-user.target
UNIT

# --now matters: this hook can run at very different points in the boot
# sequence depending on how it was invoked. From firstrun.sh it runs early
# enough that multi-user.target reaches the unit naturally on the same
# boot; from the cmdline.txt fallback, an explicit reboot follows regardless.
# But from cloud-init's runcmd (final module, often 90+ seconds in) it can
# run *after* multi-user.target was already reached, with no reboot to give
# it a second chance -- plain `enable` would then just queue it for next
# boot and never actually start it. `enable --now` starts it immediately in
# every case; the unit's own After=/Wants=network-online.target still makes
# it wait if the network genuinely isn't up yet.
systemctl enable --now mealplanner-provision.service
