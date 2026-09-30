# Using the whole app away from home (Tailscale)

The installer (`install.ps1` / `configure.py`) does the app-side steps below for you; this page is the manual route and the reference.

The shopping list already works anywhere through Home Assistant
(`ha/SETUP-SHOPPING.md`). This guide is for the rest of the app —
recipes, the meal plan, the pantry, importing — from a phone or laptop
that isn't on the home Wi-Fi, **without opening anything to the
internet.**

    Phone (Tailscale app) ──encrypted tunnel──▶ the machine running the app
      https://meal-planner.your-tailnet.ts.net          Tailscale Serve (HTTPS)
                                                    └──▶ Meal Planner on 127.0.0.1:5000

Tailscale is a private network between devices you own. Only devices
you've admitted can reach that machine; the internet can't see it at all.
Tailscale *Serve* adds a real HTTPS certificate so the phone gets a
padlock and can pin the app to its home screen. Free for personal use
(3 users, 100 devices).

Requirements: the machine running the app (a Windows PC or a Raspberry Pi)
must stay on with the app running (see "Keep the machine serving" below); each phone or laptop needs the Tailscale app,
signed in to the same account (or invited to it).

## 1. Install Tailscale on the machine running the app

1. On Windows, download from https://tailscale.com/download/windows and
   install. On a Pi, `sudo ./pi/setup-tailscale.sh` installs it and does
   steps 3 and 4 too (see docs/RASPBERRY-PI.md).
2. Sign in (Google/Microsoft/Apple/GitHub — pick one and stick with it;
   this account owns the network).
3. Confirm Tailscale shows **Connected** (the system tray on Windows,
   `tailscale status` on a Pi). The machine now has a name on your
   tailnet: for example `meal-planner`.

## 2. Turn on MagicDNS and HTTPS certificates (once, in the admin console)

At https://login.tailscale.com/admin/dns:

1. **MagicDNS** → Enable. This gives the machine its full name on your
   tailnet. It looks like **`meal-planner.your-tailnet.ts.net`** — `meal-planner` is the
   machine's name, `your-tailnet.ts.net` is the tailnet name shown on that
   page. (`tailscale status --self --json` prints it as `DNSName`.)
2. **HTTPS Certificates** → Enable. Serve needs this to get a
   certificate.

## 3. Tell the app it's behind a proxy

On a Windows PC, add one line to `%LOCALAPPDATA%\Meal Planner\.env`:

    Add-Content "$env:LOCALAPPDATA\Meal Planner\.env" "MEAL_PLANNER_BEHIND_PROXY=1"

Without it, the app treats every request as plain http, so over the
tunnel it would refuse to set a secure cookie and the login would
appear to succeed and then bounce back. Restart the app
(`python app.py`); `MEAL_PLANNER_HOST=0.0.0.0` stays as it is for the
kitchen and Home Assistant.

## 4. Start Tailscale Serve on the machine

In PowerShell (once; `--bg` makes it persist across reboots):

    tailscale serve --bg --https=443 http://127.0.0.1:5000

Check it:

    tailscale serve status

It should show `https://meal-planner.your-tailnet.ts.net (tailnet only)` proxying
to `http://127.0.0.1:5000`. **"tailnet only" is the important part** —
if you ever see "Funnel" in that output, the app is published to the
public internet; run `tailscale funnel off` immediately. This guide
never uses Funnel.

The first HTTPS request can take 10–20 seconds while the certificate
is issued; after that it renews itself.

## 5. Phones and laptops

On each device:

1. Install Tailscale (App Store / Play Store / tailscale.com) and sign
   in to the same account. To let another household member use their
   own account instead, invite them from
   https://login.tailscale.com/admin/users — their devices then join the
   same tailnet.
2. iPhone: Settings → VPN & Device Management → Tailscale → **Connect
   On Demand** on. Android: Tailscale app → Settings → **Always-on
   VPN** (or the system VPN setting). The tunnel then comes up by
   itself; there's nothing to switch on in the store.
3. Open **https://meal-planner.your-tailnet.ts.net** in the browser — `https`,
   no port number — and sign in with the household password.
4. Add it to the home screen: Safari **Share → Add to Home Screen**;
   Chrome **⋮ → Add to Home screen / Install app**. It opens straight to
   the shopping list in its own window.

Battery cost of the always-on tunnel is negligible; it's idle unless
you use it.

## 6. Check it end to end

1. On the phone, turn Wi-Fi **off** (cellular only) and open
   **https://meal-planner.your-tailnet.ts.net** (or the home-screen icon, which
   points there) → the shopping list loads with a padlock in the address
   bar.
2. Tick an item → it shows ticked on the desktop at home.
3. Open a recipe, the meal plan, the pantry — all there.
4. In the kitchen, `http://meal-planner.local:5000` still works exactly as
   before (that's the same app; Tailscale isn't involved on the LAN).
5. On the machine running the app: `tailscale serve status` shows no "Funnel".

## Keep the machine serving

- **Power (Windows PC):** Settings → System → Power → *Sleep: Never* while plugged
  in. A sleeping machine can't answer; the phone just times out. (Display
  off is fine.)
- **The app:** it has to be running. The installer offers to add it to
  your Windows Startup folder. By hand, a Task Scheduler entry that runs
  at logon works — Program `pythonw.exe`, Arguments `app.py`, Start in
  the project folder. On a Pi the app is a systemd service and starts by
  itself. Tailscale itself runs as a Windows service and needs nothing.
- **Tell "machine asleep" from "VPN off":** `https://meal-planner.your-tailnet.ts.net/healthz`
  answers `{"ok": true}` with no login. If the Tailscale app on the phone
  says *disconnected*, it's the VPN; if it's connected and `/healthz`
  times out, it's the machine.

## When something is off

- **Login succeeds then bounces back to the sign-in page (over HTTPS
  only):** `MEAL_PLANNER_BEHIND_PROXY=1` isn't set, or the app wasn't
  restarted after adding it. This is the first thing to check.
- **"Too many attempts. Try again in N minutes":** five wrong passwords
  from one device within 15 minutes locks that device out for the rest
  of the window. Wait it out (or restart the app, which clears it).
- **Certificate warning in the browser:** HTTPS Certificates aren't
  enabled in the admin console (step 2), or the first request hasn't
  finished issuing one yet — wait 20 seconds and reload.
- **Name doesn't resolve on the phone:** MagicDNS is off (step 2), or
  the phone's Tailscale isn't connected. `tailscale status` on the machine
  running the app lists every device and whether it's online.
- **Works on Wi-Fi at home but not on cellular:** the phone's VPN isn't
  actually up — check the Tailscale app, and the on-demand/always-on
  setting from step 5.

## If a household member can't run a VPN

A managed work phone may not allow Tailscale. The fallback is
Cloudflare Tunnel with Cloudflare Access in front of it: no open ports
either, but it needs a domain name (~$10/year), traffic is decrypted
at Cloudflare's edge, and Access adds its own email-code login before
the app's. The app side is the same (`MEAL_PLANNER_BEHIND_PROXY=1`);
run `cloudflared` on that machine instead of `tailscale serve`. Ask for this
guide to be written out if you need it.

## Security notes

- Nothing listens on the internet. The only way in is a device on the
  tailnet, which you admit and can revoke at
  https://login.tailscale.com/admin/machines. A lost phone: revoke it
  there (and optionally `python set_password.py` to rotate the household
  password).
- Traffic is end-to-end encrypted (WireGuard) and, on top, HTTPS from
  Serve; the session cookie is marked Secure over HTTPS.
- The app only trusts proxy headers when `MEAL_PLANNER_BEHIND_PROXY=1`,
  and then only one hop — a client can't forge its address or "https".
- Tailscale's coordination servers exchange keys and device lists; your
  data never passes through them.
