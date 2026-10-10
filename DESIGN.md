---
name: Meal Planner
description: A locally-run recipe library and meal planner, styled like a bright, well-organized kitchen counter.
colors:
  ink: "#16211b"
  paper: "#ffffff"
  paper-alt: "#f4f7f5"
  surface: "#ffffff"
  line: "#e2e8e3"
  line-soft: "#edf1ee"
  muted: "#5c6b62"
  muted-2: "#8a978f"
  accent: "#1c7a4d"
  accent-hover: "#145c3a"
  accent-bright: "#2f9463"
  accent-tint: "#e4f5ec"
  danger: "#b3261e"
  danger-hover: "#8f1e18"
  danger-tint: "#fbe9e7"
  slot-breakfast-tint: "#eaf5e8"
  slot-breakfast-ink: "#4c7a3c"
  slot-lunch-tint: "#e8f0f8"
  slot-lunch-ink: "#3c6690"
  slot-dinner-tint: "#fbeee0"
  slot-dinner-ink: "#a86a2c"
typography:
  page-title:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif"
    fontSize: "clamp(1.5rem, 1.2rem + 1.2vw, 2rem)"
    fontWeight: 800
    letterSpacing: "-0.01em"
  recipe-title:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif"
    fontSize: "clamp(1.5rem, 1.15rem + 1.3vw, 2.1rem)"
    fontWeight: 800
    letterSpacing: "-0.015em"
    lineHeight: 1.15
  card-title:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif"
    fontSize: "clamp(1.2rem, 1rem + 0.9vw, 1.6rem)"
    fontWeight: 700
    letterSpacing: "-0.01em"
  section-label:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif"
    fontSize: "1rem"
    fontWeight: 700
    letterSpacing: "-0.005em"
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
  small:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
  label:
    fontFamily: "-apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 600
rounded:
  card: "16px"
  control: "10px"
  pill: "999px"
spacing:
  row: "0.7rem"
  section: "1.25rem"
  band: "1.5rem"
components:
  card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    borderColor: "{colors.line}"
    rounded: "{rounded.card}"
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.surface}"
    rounded: "{rounded.control}"
    padding: "0.6rem 1.2rem"
  button-primary-hover:
    backgroundColor: "{colors.accent-hover}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.muted}"
    rounded: "{rounded.control}"
    padding: "0.6rem 1.2rem"
  pill-tag:
    backgroundColor: "{colors.accent-tint}"
    textColor: "{colors.accent-hover}"
    rounded: "{rounded.pill}"
    padding: "0.25rem 0.75rem"
---

# Design System: Meal Planner

## Overview

**Creative North Star: "The Open Counter"**

Meal Planner's interface reads as a bright, well-lit kitchen counter with everything laid out in reach — not the walk-in cooler's laminated shelf tags that preceded it, and not a page from a cookbook either. This is the world's third identity: it replaces "The Walk-In" (steel-gray ground, condensed uppercase shelf tags, a 7-color day-prep code) wholesale, the same way that system once replaced the original warm-paper "Family Cookbook" world. A pure-white ground, one confident forest-green accent, soft big-radius cards, and pill-shaped controls now carry the whole app. A horizontal top nav (wordmark, section links, search) replaces the left rail, and a breadcrumb trail runs under it on every interior page so the cook always knows where they are relative to Home. On the recipe page — the page that matters most while actually cooking — ingredients and numbered instructions sit side by side in one unbroken view, never behind a tab switch.

**Key Characteristics:**
- Pure white ground throughout (`--paper`), not a tinted workspace behind white cards
- One forest-green accent (`#1c7a4d`) carries every link, primary button, active nav/tab state, and step numeral — steel teal and the 7-color day-prep code are retired with the old world
- Soft, big-radius (16px) white cards with a hairline neutral border and one low, restrained shadow
- Pill-shaped buttons, badges, and search fields; rounded-rectangle (10px) primary/secondary buttons
- A horizontal top nav with search top-right and a breadcrumb trail underneath, replacing the old left sidebar
- Ingredients and instructions run in two columns on one recipe page — no ingredient/instruction tab switch
- Meal-plan slot cards use three small, real informational tints (breakfast/lunch/dinner) — the one deliberate exception to the single-accent rule, because it answers "which meal is this" the way the old day-prep code once answered "which day is this"

## Colors

A pure white ground with near-black ink, one forest-green accent, a dedicated danger red for irreversible actions, and three small meal-type tints used only on the weekly meal-plan grid.

### Primary
- **Forest Green** (`#1c7a4d`): the accent. Used for links, primary buttons, active nav/breadcrumb-adjacent states, the recipe step numerals, focus rings, and text selection. Hover deepens to `#145c3a`; a brighter `#2f9463` marks focus rings and "today" emphasis; a pale tint `#e4f5ec` backs pills, active nav items, and hover states.
- **Danger Red** (`#b3261e`): reserved for destructive actions only (delete a file, clear a shopping list, remove a meal). Never used for anything reversible.

### Neutral
- **Ink** (`#16211b`): primary text, near-black with a faint warm-green cast.
- **Paper** (`#ffffff`): the page ground — `body` background and every card's own surface. There is no separate tinted workspace layer; white is the whole canvas.
- **Paper Alt** (`#f4f7f5`): card header strips, the dashed empty-state well, and admin-table header rows — the only surface that reads as "recessed."
- **Muted** (`#5c6b62`) / **Muted 2** (`#8a978f`): secondary text and placeholder text, in descending emphasis.
- **Line / Line Soft** (`#e2e8e3` / `#edf1ee`): card borders and hairline row dividers.

### Meal-Slot Tints (weekly meal-plan grid only)
Breakfast `#eaf5e8` tint / `#4c7a3c` ink &middot; Lunch `#e8f0f8` tint / `#3c6690` ink &middot; Dinner `#fbeee0` tint / `#a86a2c` ink. Applied only as a meal-slot card's header background and icon color on the Meal Plan week grid — real information (which meal this slot is), never decoration, and never used as body text color or full-card background elsewhere.

### Named Rules
**The One Accent Rule.** Forest green is the only color carrying actions, links, and active/selected state anywhere outside the meal-plan grid. The meal-slot tints are the one named exception, scoped to that single grid and to header chrome only.

## Typography

**Font:** a system-UI stack (`-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif`) for every weight of text, headings included. Meal Planner runs locally and is used offline in a kitchen; nothing in the type system depends on a network font request, so there is one face family throughout rather than a separate display face.

**Character:** confident but plain — bold weight and tight negative tracking carry hierarchy instead of a second typeface. Sentence case throughout; the old world's uppercase condensed labels are retired.

### Hierarchy
- **Page Title** (800, clamp 1.5–2rem, tracking -0.01em): top-of-page headings on section landing pages (Recipes, Pantry, Shopping List, Meal Plan).
- **Recipe Title** (800, clamp 1.5–2.1rem, tracking -0.015em): the recipe detail page's own `h1`.
- **Card Title** (700, clamp 1.2–1.6rem, tracking -0.01em): a card header (`.divider-band h2`) — category, aisle, or day name.
- **Section Label** (700, 1rem): in-page subheadings ("Ingredients", "Instructions", "Notes"), each set off with a 2px green underline rule instead of a color or uppercase treatment.
- **Body** (400, 1rem): ingredient lines, step text, prose.
- **Small** (400, 0.875rem): notes, substitutions, meta text, breadcrumbs.
- **Label** (600, 0.875rem): buttons, pills, and badges — sentence case, semibold, never uppercase.

### Named Rules
**The Weight-Over-Face Rule.** Hierarchy comes from size, weight, and negative tracking on one system font, never from a second typeface or an uppercase transform.

## Layout

A horizontal top nav (`.topnav`, sticky, white, 1px bottom border) holds the wordmark, section links, and a top-right search field, replacing the old 220px left sidebar entirely. A breadcrumb trail (`.breadcrumb`, "Home / Section / Page") runs directly beneath the nav on every interior page; the Recipes landing page is Home and carries no breadcrumb. Page content (`.app-content`) is centered with a 1180px max width — wider than the old world's 760px column, to make room for the recipe page's two-column layout and the meal-plan week grid.

The recipe detail page opens with a hero row (title, category pill, servings/oven meta, and — when present — a large rounded photo) followed immediately by ingredients and instructions in a two-column grid (`.recipe-columns`, roughly 1fr / 1.35fr) inside one card, so both are visible without switching tabs; the grid collapses to one stacked column under 800px, and the hero row itself stacks (photo above text) under 720px so the meta row never crowds against the image. The Meal Plan page is a seven-column week grid (one column per day, three stacked meal-slot cards per day), replacing the old world's week-strip-plus-one-focused-day pattern; it steps down to 4 columns at 900px and 2 at 560px.

## Elevation & Depth

One soft, low shadow (`0 1px 2px rgba(22,33,27,.04), 0 10px 24px rgba(22,33,27,.07)`) on every card and floating panel, restrained enough to read as "resting on white" rather than "lifted." A slightly stronger pop shadow (`0 4px 10px rgba(22,33,27,.08), 0 16px 32px rgba(22,33,27,.1)`) is reserved for transient overlays (the recipe detail "more actions" menu) that sit above other content.

### Named Rules
**The Two-Shadow Rule.** Cards and panels use the resting shadow; only a popover that overlaps other content earns the pop shadow. Neither ever stacks or intensifies for hierarchy — hierarchy comes from size, position, and color, not heavier shadows.

## Shapes

Rounded throughout, and rounder than the old world: 16px on cards (was 10px), 10px on buttons/inputs/controls (was 6px), full pill radius on badges, tags, search fields, and most standalone buttons (Search, Add, meal-slot Edit/Remove, nav links). Primary form-adjacent buttons (Save, Scale, Assign to Calendar) stay rounded-rectangle at 10px rather than full pill, so a full pill consistently signals "compact action" and a rounded-rectangle signals "form submit."

## Components

### Top Nav (`.topnav`)
- **Style:** white, sticky, 1px bottom border. Wordmark + small pot icon on the left, section links inline, a pill search field pinned right.
- **Active state:** the current section's link gets an `accent-tint` pill background and `accent-hover` text — no underline, no uppercase.
- **Mobile:** links become a horizontally scrollable row beneath the wordmark/search row at ≤860px; labels stay visible, never icon-only.

### Breadcrumb (`.breadcrumb`)
- **Style:** small, muted, `/`-separated trail starting at "Home"; the current page is ink-colored and not a link. Every interior page carries one; the Recipes landing page (effectively Home) does not.

### Cards (`.divider`)
- **Shape:** 16px radius, white body, 1px `line` border, resting shadow.
- **Header:** `paper-alt` strip, no colored edge or punched-hole detail (both retired with the old world) — just a bold card title on the left and a muted count/meta on the right.
- **Body:** rows or subsections in white, hairline dividers between rows, none after the last.

### Buttons
- **Primary:** accent fill, white text, 10px radius, semibold sentence-case label.
- **Secondary:** white fill, `line` border, muted text, hover border shifts to `danger` when the action is a cancel/discard, to `accent` otherwise.
- **Compact pill actions** (Search, Add, Edit, Remove, aisle/servings actions): full pill radius, same color logic as primary/secondary at a smaller scale.

### Meal-Slot Card (`.meal-slot-card`, Meal Plan grid)
- **Shape:** 10px-radius card, tinted header (breakfast/lunch/dinner), a recipe photo or tinted placeholder icon, title, and small Edit/Remove text actions when filled; a dashed-top "Add Recipe" prompt when empty.
- **Signature behavior:** the header tint is the only place outside the accent where color carries real meaning (see Named Rules, Colors).

### Recipe Columns (`.recipe-columns`, recipe detail)
- **Shape:** two columns inside one card, a 1px divider between them, ingredients left and instructions right; stacks to one column under 800px.
- **Steps:** numbered with a filled accent-green circular numeral (`.step-row-main::before`), bold enough to scan while hands are messy.

### Destructive Confirm
- Unchanged `<details>`-based reveal pattern, in the dedicated danger-red voice (pill summary, danger-fill confirm button) — the one place danger red appears.

## Do's and Don'ts

### Do:
- **Do** keep the page ground pure white (`--paper: #ffffff`) — cards are distinguished by border and shadow only, never by a tinted workspace background.
- **Do** keep ingredients and instructions on the recipe page in one two-column view; never move either behind a tab.
- **Do** reserve the breakfast/lunch/dinner tints for the Meal Plan grid's slot-card headers only.
- **Do** keep every button, pill, and badge label in sentence case, semibold — never uppercase.
- **Do** show a breadcrumb on every interior page; the Recipes landing page is the one exception.

### Don't:
- **Don't** introduce a second accent for ordinary UI — forest green is the only action/link color outside the meal-plan grid.
- **Don't** bring back the old world's condensed uppercase type, punched-hole card detail, or 7-color day-prep code — that identity is retired, not layered underneath this one.
- **Don't** add a heavier shadow to imply more hierarchy — the resting shadow is for cards, the pop shadow is for overlays only, and neither stacks.
- **Don't** redeclare the tokens per surface; every screen reuses the apps' shared theme, which mirrors the tokens above (the desktop's `DesignTokensTest` checks it against this file).
