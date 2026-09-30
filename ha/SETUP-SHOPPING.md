# Using the shopping list from anywhere, through Home Assistant

The installer (`install.ps1` / `configure.py`) does the app-side steps below for you; this page is the manual route and the reference.

The Meal Planner mirrors its shopping list into a Home Assistant to-do
list. Because your phones already reach Home Assistant from anywhere
through Nabu Casa with the companion app, that to-do list *is* the
shopping list you use in the store — tick things off there and the
ticks show up in the Meal Planner at home, and vice versa. Nothing new
is installed on the phone and nothing new is opened to the internet.

    Phone (HA app, via Nabu Casa) ⇄ Home Assistant to-do list ⇄ Meal Planner on your PC

The Meal Planner's own list stays the source of truth: the app pushes
its changes to HA, and an HA automation posts the list back whenever it
changes there. Both sides reconcile rather than replay, so nothing
loops.

Requirements: Home Assistant at home with a Nabu Casa subscription, the
HA companion app on each phone, and the Meal Planner running on a PC
that HA can reach over the LAN (`MEAL_PLANNER_HOST=0.0.0.0` in the app's
`.env`, as for the Alexa skill). If you set up the "chef" Alexa skill
already, steps 1 and 3 reuse what you did then.

## 1. Give Home Assistant a token for the app (skip if the Alexa skill is set up)

On the PC:

    python set_api_token.py

Copy the printed `meal_planner_auth: "Bearer …"` line into Home
Assistant's `secrets.yaml`. This is the token HA uses to call the app.

## 2. Create the to-do list in Home Assistant

Settings → Devices & services → **Add integration** → **Local To-do** →
name it `Shopping List`. The entity id becomes `todo.shopping_list`.

Check the id under Settings → Entities: if you already have Home
Assistant's built-in *Shopping list* integration, it owns
`todo.shopping_list` and the new list will be `todo.shopping_list_2`.
Either remove the built-in one first (it has no aisle line, so it's
not a substitute) or keep the `_2` id and
use it in step 3, in `ha/shopping-list.yaml`, and on the dashboard.

## 3. Give the app a token for Home Assistant

In HA click your user name at the bottom of the sidebar → **Security**
→ **Long-lived access tokens** → **Create token**, name it `Meal
Planner`, copy it (shown once). Make a new one even if the Alexa skill
already has one: HA tokens can't be scoped, so the only control you
have is revoking one without breaking the other — the Alexa token
lives in Amazon's cloud, this one lives on your PC. (The token going
the *other* way, `meal_planner_auth` in step 1, is deliberately the
same for both.) On the PC:

    python set_ha_link.py

Enter HA's address as the PC sees it (usually
`http://homeassistant.local:8123`), paste the token, accept the entity
id. The script reads the list once to prove the link before saving
anything, then tells you to restart the app.

## 4. Send changes from HA back to the app

`ha/shopping-list.yaml` has two top-level keys. Where they go depends
on how your configuration is laid out:

- **`rest_command:`** → `configuration.yaml`. If you already have a
  `rest_command:` block from the Alexa skill, add
  `meal_planner_shopping_sync:` *inside* it (same indentation as
  `chef_shopping_add:`) rather than making a second block.
- **`automation:`** → almost every install has
  `automation: !include automations.yaml` in `configuration.yaml`, which
  means `automations.yaml` *is* the automation section and a second
  `automation:` key isn't allowed. Append the entry — everything from
  `- alias:` down, at column 0, without the `automation:` line — to
  `automations.yaml`. It carries an `id`, so it appears in Settings →
  Automations like a UI-made one.

Or, to keep the file whole: turn on packages once in
`configuration.yaml` (`homeassistant:` → `packages: !include_dir_named
packages`) and drop `shopping-list.yaml` into a `packages/` folder
untouched; packages merge their automations with `automations.yaml`.

To get the text onto the HA machine, on the PC:

    Get-Content "<path to the repo>\ha\shopping-list.yaml" | Set-Clipboard

then paste in HA's File editor. (Windows has no default app for
`.yaml`; open it with Notepad or VS Code if you want to read it.)

The URL points at the machine running the app by its mDNS name
(`meal-planner.local`); if that
ever stops resolving from HA, use the PC's LAN address and give it a
DHCP reservation.

Developer tools → **Check configuration**, then **Restart** (or, if only
`automations.yaml` changed, Developer tools → YAML → reload
Automations).

## 5. Put the list on your phone

Settings → Dashboards → **Add dashboard** → name it `Shopping` → open
it → Edit → **Add card** → **To-do list** → entity
`todo.shopping_list`, title "Shopping list" → Save. In the HA
companion app the dashboard appears in the sidebar; on iOS/Android you
can also set it as the app's default page (companion app settings →
General → Default dashboard).

## 6. Check it end to end

Restart the app on the PC (`python app.py`). Then:

1. On the desktop, add an item to the shopping list → within a few
   seconds it appears in the HA to-do card, with its aisle as the
   second line.
2. In the HA app, tick it → the desktop shopping list shows it ticked on
   reload (the Meal Planner page also has a **Sync with Home
   Assistant** button and a line saying when it last synced).
3. In the HA app, add an item by typing into the card → it appears on
   the desktop with a sensible aisle; "2 lb ground beef" or "two pounds
   of ground beef" both land with the amount parsed.
4. In the HA app, delete an item → gone from the desktop.
5. On the desktop, clear the list → the HA card empties.
6. Turn Wi-Fi off on the phone and repeat 2 on cellular.
7. If you use the Alexa skill: "Alexa, ask my chef to add milk to the
   cart" → milk shows up in both places.

## When something is off

- **The desktop says "Home Assistant unreachable since …"**: the PC
  can't reach HA at the URL you gave `set_ha_link.py`, or the token was
  revoked. Fix, then press **Sync with Home Assistant**.
- **Ticks in the HA app never reach the desktop**: HA can't reach the
  PC. Settings → Automations → "Meal Planner shopping list -> app" →
  ⋮ → **Run actions**, then look at its trace: a transport error on the
  `rest_command` step means the PC is off, the app isn't running, or
  `meal-planner.local` isn't resolving from HA. (Don't call the
  `rest_command` by hand with a made-up `items` list — the app applies
  whatever list it is given, and an empty one would delete every
  mirrored item.)
- **An item you deleted on the desktop came back**: shouldn't happen —
  the app remembers what it deleted and removes it from HA on its next
  pass. If it does, press **Sync with Home Assistant** and report it.
- **Items ticked on the phone with no signal**: the HA app can't queue
  changes offline; the tick fails in the app and you redo it when signal
  returns. Nothing is lost on either side.
- **"Already in My Kitchen" items aren't in HA**: by design — the store
  list is what needs buying.
