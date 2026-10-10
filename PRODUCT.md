# Product

<!-- impeccable:product-schema 1 -->

## Platform

desktop and mobile (a Windows desktop app and an Android app, Compose Multiplatform)

## Users

The user themselves plus other household members (partner/family) who check or use it too — above all, standing in a grocery store aisle working through the shopping list on a phone, away from home and off the home network. There are no per-person accounts. The job: keep a personal recipe collection organized, turn it into a weekly meal plan, and generate a shopping list that already knows what's in the pantry.

## Product Purpose

Meal Planner is a locally-run personal tool for managing a home cook's own recipe collection and turning it into a weekly meal plan and shopping list. It was built as four progressively-layered sub-projects — Recipe Library → Meal Calendar → Pantry → Shopping List — each building on the one before it, first as a Python web server and now as native apps.

## Positioning

Recipes are stored as individual Open Recipe Format (ORF) YAML files rather than locked inside a proprietary database or cloud service — the user owns and can hand-edit every recipe as plain text, and a sync step indexes the recipe files into a local SQLite database for browsing and search. On top of that it layers a pantry-aware shopping list (crossing a week's assigned meals against what's already on hand) and import from legacy Meal Master (`.mmf`) files and, through a Chrome extension, from recipe websites. No mainstream consumer recipe app combines "recipes as plain YAML files you own" with a pantry-aware shopping list.

## Operating Context

Two standalone apps, no server and no cloud copy of the data. The Windows desktop app (installed per user from an MSI, living in the tray) is the household's hub: its library is a folder of `*.yaml` recipe files in `Documents\Meal Planner\recipes\`, indexed into its own SQLite database and watched for changes, and while it runs it answers the Chrome extension and Alexa (through Home Assistant) on port 5000 and sends the week's meals to Google Calendar. The Android app keeps its own library in its own database on one phone, with ORF YAML as its import and export format. Used in three physical contexts, in this order of importance for the mobile design: on a phone in the grocery store working the shopping list (one thumb, cart in the other hand, spotty signal, outside the home LAN); at a desktop at home for planning and browsing recipes; and on a phone in the kitchen while actually cooking (hands messy, following steps). Because the phone app holds its own data, the store needs no connection. Nothing the user creates is ever written into the install folder.

## Capabilities and Constraints

- No accounts. The desktop's only secret of its own is the Alexa token (and, when used, a Google sign-in sealed with Windows DPAPI), kept in `%LOCALAPPDATA%\Meal Planner\`.
- A recipe's identity is its `recipe_uuid` field, not its filename; renaming a `.yaml` file is always safe and preserves calendar assignments, etc.
- Only files ending in `.yaml` are auto-indexed from `recipes/`; `.yml` is silently ignored.
- Recipe import (`.yaml`/`.yml`, Meal Master `.mmf`, a backup zip, or a recipe sent from the Chrome extension) is staged through a review screen — nothing is written until the user confirms.
- Categories and subcategories are free-form text per recipe, with a default suggested list (`Appetizers`, `Soups & Stews`, `Salads`, `Main Dishes`, `Side Dishes`, `Breads & Baking`, `Desserts`, `Beverages`, `Sauces & Condiments`, `Breakfast`; subcategory list covers proteins).
- The meal calendar assigns a recipe to a date + slot; adding a week's assigned meals to the shopping list crosses them against current pantry contents. The list is additive — adding a week never clears what is already on it; only the user clears it.
- Both apps check GitHub Releases for signed updates; nothing installs without the user's say-so.

## Brand Commitments

Named "Meal Planner" in the window title and the app's name, paired with a cookbook-and-calendar mark (`icon.png`, made into the desktop's tray and installer icons). No other brand identity (voice, extended palette) has been established beyond the DESIGN.md visual system.

## Evidence on Hand

The repo ships no recipes; golden Open Recipe Format and Meal Master cases live under `tests/fixtures/`. The working library is whatever the user has added. There is no testimonial, pricing, or marketing content — this is a personal tool, not a product with customers, and future work must not fabricate any.

## Product Principles

1. Personal, not multi-tenant: build for one household, on that household's own PC and phones. Never add features that assume unrelated users, public deployment, or per-person accounts.
2. This is a fun/learning project as much as it is a tool — favor a codebase and UI that stay simple and legible over polish aimed at a product-market need that doesn't exist here.
3. On the desktop, the YAML files in `recipes/` are the source of truth; the SQLite database is a rebuildable index/cache over them, never the other way around.
4. Design for the three real physical contexts of use, and let the first one win any conflict: a phone in the grocery store working the shopping list (the primary mobile screen — fast, usable one-handed, with aisle grouping and check-off that survive a restart); calm reference at a desktop while planning; and a phone with messy hands in the kitchen while actually cooking.
