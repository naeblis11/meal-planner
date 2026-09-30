# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

The user themselves plus other household members (partner/family) who check or use it too — above all, standing in a grocery store aisle working through the shopping list on a phone, away from home and off the home network. There are no per-person accounts; one shared household password lets everyone in as the same "user." The job: keep a personal recipe collection organized, turn it into a weekly meal plan, and generate a shopping list that already knows what's in the pantry.

## Product Purpose

Meal Planner is a locally-run personal tool for managing a home cook's own recipe collection and turning it into a weekly meal plan and shopping list. It was built as four progressively-layered sub-projects — Recipe Library → Meal Calendar → Pantry → Shopping List — each building on the one before it.

## Positioning

Recipes are stored as individual git-tracked Open Recipe Format (ORF) YAML files rather than locked inside a proprietary database or cloud service — the user owns and can hand-edit every recipe as plain text, and a sync step indexes `recipes/*.yaml` into a local SQLite database for browsing and search. On top of that it layers a pantry-aware shopping list (crossing a week's assigned meals against what's already on hand) and import from legacy Meal Master (`.mmf`) files. No mainstream consumer recipe app combines "recipes as plain YAML files under your own git repo" with a pantry-aware shopping list.

## Operating Context

Runs locally, started with `python app.py` — not `flask run`, since the initial `recipes/` folder sync only happens in the `__main__` block. A single shared SQLite database (`mealplanner.db`) backs the whole household; there is no per-person data. Used in three physical contexts, in this order of importance for the mobile design: on a phone in the grocery store working the shopping list (one thumb, cart in the other hand, spotty signal, outside the home LAN); at a desktop at home for planning and browsing recipes; and on a phone in the kitchen while actually cooking (hands messy, following steps). Because the store is off the home network, the app must be reachable from the phone over the internet through a secured path the household controls (a VPN such as Tailscale, an authenticated tunnel, or an HTTPS reverse proxy) — never exposed unauthenticated, and never dependent on a third-party cloud copy of the data. Recipes live one-file-per-recipe as `*.yaml` under the user's data folder (`Documents\Meal Planner
ecipes\`, seeded from the repo's `recipes/` on first run; `paths.py` resolves every per-user location and `MEAL_PLANNER_DATA_DIR` overrides it), hand-authored or dropped in, then indexed via "Rescan recipes/ folder" (or automatically on app startup). Nothing the user creates is ever written into the install folder, so the app can ship as an installer.

## Capabilities and Constraints

- One shared household password, no per-person accounts. The password is stored only as a salted scrypt hash in a `.env` under the user's AppData folder (written by `set_password.py`, located by `paths.py`) alongside the session-signing secret; the app refuses to start without both. Meant to make exposing the app on the home Wi-Fi safe, not to be a multi-user system.
- A recipe's identity is its `recipe_uuid` field, not its filename; renaming a `.yaml` file is always safe and preserves calendar assignments, etc.
- Only files ending in `.yaml` are auto-indexed from `recipes/`; `.yml` is silently ignored.
- Recipe import (native `.yaml`/`.yml` upload or legacy Meal Master `.mmf` upload) is staged through a review screen — the user confirms/edits category and subcategory before anything is written to `recipes/`.
- Categories and subcategories are free-form text per recipe, with a default suggested list (`Appetizers`, `Soups & Stews`, `Salads`, `Main Dishes`, `Side Dishes`, `Breads & Baking`, `Desserts`, `Beverages`, `Sauces & Condiments`, `Breakfast`; subcategory list covers proteins).
- The meal calendar assigns a recipe to a date + slot; adding a week's assigned meals to the shopping list crosses them against current pantry contents. The list is additive — adding a week never clears what is already on it; only the user clears it.
- The shopping list can be mirrored into a Home Assistant to-do list (`ha_sync.py`) so the household works it from the HA companion app in the store, through a remote connection to Home Assistant, such as Nabu Casa. The app's database stays the source of truth: it pushes its changes to HA, and an HA automation posts the list back; both directions reconcile rather than replay. "Already in My Kitchen" rows are not mirrored.
- `mealplanner.db` is the only app database.

## Brand Commitments

Named "Meal Planner" in the page title and top-nav wordmark, paired with a cookbook-and-calendar mark (`icon.png`, served small as `static/icon-nav.png` and as `static/favicon.ico`). No other brand identity (voice, extended palette) has been established beyond the DESIGN.md visual system.

## Evidence on Hand

The repo ships no recipes: `recipes/` holds only a README, and the sample Open Recipe Format files live under `tests/fixtures/`. The working library is whatever the user has added to their data folder. There is no testimonial, pricing, or marketing content — this is a personal tool, not a product with customers, and future work must not fabricate any.

## Product Principles

1. Personal, not multi-tenant: build for one household, served from one trusted machine and reached from that household's own devices — at home or, through a secured remote path, from anywhere. Never add features that assume unrelated users, public deployment, or per-person accounts.
2. This is a fun/learning project as much as it is a tool — favor a codebase and UI that stay simple and legible over polish aimed at a product-market need that doesn't exist here.
3. The YAML files in `recipes/` are the source of truth; the SQLite database is a rebuildable index/cache over them, never the other way around.
4. Design for the three real physical contexts of use, and let the first one win any conflict: a phone in the grocery store working the shopping list (the primary mobile screen — fast to load, usable one-handed, tolerant of a flaky connection, with aisle grouping and check-off that survive a page reload); calm reference at a desktop while planning; and a phone with messy hands in the kitchen while actually cooking.
