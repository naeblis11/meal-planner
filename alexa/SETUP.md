# Setting up the "chef" Alexa skill

The app side (step 1) happens in Meal Planner's Settings on the PC that runs it. The old Python server's installer (`install.ps1` / `configure.py`) did it for that server; the frozen Raspberry Pi is covered at the end of step 1. This page is the manual route and the reference.

Voice control for the Meal Planner: "Alexa, ask my chef to add milk to the
cart", "…add olive oil to the pantry", "…plan tacos for dinner on Thursday".

Four pieces talk to each other:

    Echo → Alexa skill "chef" (a few lines of Python hosted by Amazon)
        → your Home Assistant (via Nabu Casa) → the Meal Planner on your PC

The hosted skill code is a plain forwarder: Home Assistant's `/api/alexa`
endpoint only accepts requests that carry a Home Assistant access token,
and Alexa can't send one itself, so the skill adds it and passes
everything else through untouched. No AWS account is needed: "Alexa-
hosted" skills run for free inside the Alexa Developer Console.

Requirements: Home Assistant at home with a Nabu Casa subscription
(Alexa integration enabled), an Amazon developer account (free, same
Amazon account as your Echo devices), and the Meal Planner running on a
PC that Home Assistant can reach over the LAN.

## 1. Give the Meal Planner a voice token

Home Assistant calls Meal Planner on the PC that runs it (the Windows
desktop app), on port 5000.

- **Already set up for the old Python server on this PC?** Nothing to do
  here: the desktop app reads the same token from
  `%LOCALAPPDATA%\Meal Planner\.env`, so the line already in Home
  Assistant's `secrets.yaml` keeps working. (A token must be 16
  characters or more; the one `set_api_token.py` makes is 64. A shorter
  one is ignored, and the app's log says so without showing it. Quotes
  around the value in the file are fine.)
- **Otherwise:** open Meal Planner on the PC, go to **Settings**, and
  under "Chrome extension and Alexa" choose **Create a token**. It shows
  a `meal_planner_auth: "Bearer ..."` line once, with **Copy**; you'll
  paste it into Home Assistant in step 3. Choose **Done** when you have
  it. If you leave Settings first, the line is gone: choose **Make a new
  token** (it takes the place of Create once a token exists). That
  replaces the token in use, so Home Assistant stops working until you
  paste the new line into its `secrets.yaml` and restart it.

With a token set up, the app listens on the home network by itself; no
`MEAL_PLANNER_HOST` setting is needed. Settings then says "Listening on
port 5000 on your home network". The first time, Windows may ask whether
to allow Meal Planner: allow it on private networks only. If Settings
says "Port 5000 is in use" instead, the old Python server is still
running: stop it, and the app takes the port within a minute (see
`docs/WINDOWS.md`).

Home Assistant reaches the PC by its mDNS name, `<pc-name>.local` (the
PC's name is under Windows Settings > System > About). If that name ever
fails to resolve, use the PC's LAN address instead (`ipconfig` on the PC;
say `192.168.1.50`), and give the PC a DHCP reservation on your router
so the address doesn't change.

Quick check from any other machine on the network (replace the name and
the token):

    curl -X POST http://<pc-name>.local:5000/api/voice/shopping-list \
      -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
      -d "{\"item\": \"test item\"}"

You should get `{"ok":true,"speech":"Added test item to your shopping list, under Uncategorized."}`.
Remove the test item from the list in the app afterwards.

**The frozen Raspberry Pi instead:** the Pi on the `pi` branch serves
the same three addresses at `http://meal-planner.local:5000`. There, as
before, run `python set_api_token.py`, set `MEAL_PLANNER_HOST=0.0.0.0` in
its `.env`, restart it, and keep `meal-planner.local` in the URLs of
step 3 (see `docs/RASPBERRY-PI.md` on the `pi` branch).

## 2. Create the skill in the Alexa Developer Console

1. Go to https://developer.amazon.com/alexa/console/ask and sign in with
   the Amazon account your Echo devices use.
2. **Create Skill** → name `Chef` → primary locale English (US) →
   type **Custom** → hosting **Alexa-hosted (Python)** → template
   **Start from scratch** → Create. (Provisioning the hosted code takes
   a minute or two.)
3. In the left menu open **Interaction Model → JSON Editor**. Replace
   the whole contents with the contents of `alexa/interaction-model.json`
   and click **Save Model**, then **Build Model** (takes a minute).
   - The invocation name is "my chef" (Amazon reserves one-word names
     such as "chef" for brands, and its build step rejects them), so
     every command starts "Alexa, ask my chef to …" or "Alexa, tell my
     chef to …".
4. In Home Assistant, create the access token the skill will use: click
   your user name at the bottom of the sidebar → **Security** →
   **Long-lived access tokens** → **Create token**, name it `Alexa chef
   skill`, and copy it (it's shown once). This token is created by your
   HA user and grants that user's access to Home Assistant, so it goes
   into the skill and nowhere else; if it ever leaks, delete it from the
   same page.
5. Open the **Code** tab. In the file tree on the left:
   - Open `lambda_function.py` and replace its whole contents with
     `alexa/lambda_function.py` from this repo.
   - Create a new file `config.py` in the same `lambda/` folder (right-
     click the folder → New File) with the contents of `alexa/config.py`,
     then fill in the two values: `BASE_URL` is your Nabu Casa remote
     URL (Home Assistant → Settings → Home Assistant Cloud → Remote
     control; `https://….ui.nabu.casa`, no trailing path) and
     `LONG_LIVED_ACCESS_TOKEN` is the token from the previous step.
   - Click **Save**, then **Deploy** and wait for "Deployment successful".

   There is no Endpoint tab step: an Alexa-hosted skill points at its own
   code automatically.
6. The skill is a development skill on your account; it's automatically
   enabled on every Echo signed in to that account. No certification or
   publishing.

## 3. Configure Home Assistant (the HOME instance)

1. In `secrets.yaml` add the line printed in step 1:

       meal_planner_auth: "Bearer <token>"

2. Copy `alexa/home-assistant.yaml` into your configuration — either as
   a package or by pasting its three top-level keys (`alexa:`,
   `intent_script:`, and `rest_command:`) into `configuration.yaml`. If
   you already have an `alexa:` block (for example `alexa: smart_home:`),
   keep yours — the bare `alexa:` here only exists to turn the endpoint
   on, and isn't needed when one is already present. The `intent_script:`
   and `rest_command:` keys are the ones that actually matter; merge or
   add them regardless.
   - The `action:` step key used inside `intent_script:` needs Home
     Assistant 2024.8 or newer; on an older install rename every step's
     `action:` to `service:`.
3. Point the three `rest_command` URLs at the PC that runs Meal Planner.
   They ship as `http://meal-planner.local:5000/...`, the Raspberry Pi's
   name: change the host in all three to `<pc-name>.local` (or the PC's
   LAN address from step 1), keeping port 5000 and the path. Nothing else
   in the file changes. On the frozen Pi, leave them as they are.
4. Developer tools → **Check configuration**, then **Restart**.
5. Sanity-check HA → PC before involving Alexa: Developer tools →
   **Actions** (called Services on older HA), pick
   `rest_command.chef_shopping_add`, switch to YAML mode, enter
   `item: test item`, and perform it. "test item" should appear on the
   shopping list in the app (remove it afterwards). If it errors with a
   connection failure, Home Assistant isn't reaching the PC: check that
   Meal Planner is running and its Settings says it is listening on your
   home network, and if `<pc-name>.local` doesn't resolve, change the
   three URLs to the PC's LAN address from step 1, then check and restart
   again. A 401 means the
   `meal_planner_auth` secret doesn't match the token on the PC.

## 4. Test

In the Alexa Developer Console open the **Test** tab, set it to
**Development**, and type:

    open my chef

Alexa should answer "What should I add? Say, add milk to the cart, add
olive oil to the pantry, or plan tacos for Thursday." (That greeting is
the only sentence the hosted skill code speaks by itself; everything
after it comes from Home Assistant and the app.) Then type:

    ask my chef to add milk to the cart

Alexa should ask "How much milk?" — answer "two gallons" or "skip" —
then "Which aisle is milk in?" — answer "dairy" or "skip" — and finally
say "Added 2 gallons of milk to your shopping list, under Dairy & Eggs."
(The aisle slot understands the spoken form "Dairy and Eggs"; the app
still files it under its own "Dairy & Eggs" aisle.) Check the shopping
list in the app. Then try:

    tell my chef to add olive oil to the pantry
    ask my chef to plan tacos for dinner on thursday

The Test tab shows the exact JSON Alexa sent and what came back, which
is the best place to debug slot values.

### Phrasing on a real Echo

"Add … to the cart" and "add … to my shopping list" are also *built-in*
Alexa commands (the Amazon cart and Alexa's own shopping list), and on a
real device Alexa sometimes takes those for itself even after "ask my
chef to" — you'll know because the item turns up in your Amazon cart.
Two ways around it:

- Use wording Amazon doesn't own. The skill understands all of these:
  "Alexa, tell my chef we need chicken", "…we're out of chicken",
  "…add chicken to the grocery list", "…put chicken on the list",
  "…buy two pounds of chicken".
- Open the skill first: "Alexa, open my chef" → it asks what to add →
  "add chicken to the cart". Inside the session every phrasing goes to
  the skill.

## Troubleshooting

| Alexa says | Likely cause |
|---|---|
| "I couldn't reach the meal planner." | Two hops can say this. Most often it's Home Assistant's own fallback: the PC is off, the app isn't running, no token is set up in Meal Planner's Settings (without one it listens on the PC only), Windows' firewall blocked it, the address in the YAML is wrong (it must name the PC, not the Pi's `meal-planner.local`), or the app took longer than 5 seconds to answer — check Meal Planner's log (`Documents\Meal Planner\.cache\meal-planner.log`) and Home Assistant → Settings → System → Logs, and re-run the curl check from step 1 from the HA machine if you can. The hosted skill says the same sentence when *it* can't reach Home Assistant at all (Nabu Casa remote access off, HA down, `BASE_URL` wrong, HA answered with an error status such as 401 for a bad token, or no answer within 6 seconds); in that case the Test tab's JSON shows the request never got a proper answer from HA, and HA's log shows at most an invalid-authentication warning (a bad token is logged by HA). |
| Nothing from the skill — the item appears in your **Amazon** cart or Alexa's own shopping list | Alexa's built-in shopping feature took the sentence. Use "tell my chef we need …" / "…add … to the grocery list", or open the skill first ("Alexa, open my chef"); see *Phrasing on a real Echo* above. |
| "The meal planner refused the request. Check the token in Home Assistant." | The app got the request and rejected it: the `meal_planner_auth` secret in `secrets.yaml` doesn't match the token on the PC. Paste the line from step 1 again. If you no longer have it, choose **Make a new token** in Meal Planner's Settings and paste the new line into `secrets.yaml` (on the frozen Pi: `python set_api_token.py`, then restart it). |
| "The meal planner's voice API isn't set up yet." | The app got the request, but no token is set up in it. Choose **Create a token** in Meal Planner's Settings (step 1). Without a token the PC app listens on the PC only, so from Home Assistant you would more often hear "I couldn't reach the meal planner." (On the frozen Pi: run `python set_api_token.py` and restart the app.) |
| "I didn't catch what to add." | Alexa sent an empty item — usually a mis-hear. Say it again with the item name at the end of the sentence. |
| "I couldn't find a recipe like …" / "I found A and B. Which one?" | Say more of the recipe's name as it appears in the app. |
| "I need a specific day, like Thursday." | You said "this weekend" or "next month"; the calendar plans single days. |
| Skill doesn't respond at all / "There was a problem with the requested skill's response." | Alexa's generic error means the hosted skill code itself didn't answer — a wrong URL or token, remote access being off, and HA error pages all get turned into "I couldn't reach the meal planner." (previous row) instead. So: the code failed to deploy or crashed on start (most often a typo in the pasted `config.py`, such as a missing quote — open the Code tab, check the Deploy status and the **Logs** entry for the traceback, fix, Save, Deploy again); or Home Assistant answered with JSON that isn't an Alexa response (unusual; the Test tab shows the raw reply). |
| "This intent is not yet configured within Home Assistant." | Home Assistant received the request but has no `intent_script` entry for that intent: the YAML from step 3 isn't loaded (not pasted, not included as a package, or HA wasn't restarted after adding it). Check configuration and restart HA. |

**Note on exposure:** Home Assistant's `/api/alexa` endpoint is
protected by HA's own authentication, the same as the rest of its API;
that's why the skill code has to attach a token at all. The sensitive
item in this setup is that long-lived access token inside the hosted
skill's `config.py`: it grants the HA user who created it full access
to Home Assistant, not just to this endpoint. Keep it in the skill only,
and revoke it from your HA profile's Security page if the skill or your
Amazon developer account is ever compromised. The separate token between
HA and the Meal Planner (step 1) protects the PC and grants nothing else.
