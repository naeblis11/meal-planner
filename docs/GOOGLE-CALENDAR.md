# Putting the week's meals on a Google calendar

The installer (`install.ps1` / `configure.py`) does the app-side steps below for you; this page is the manual route and the reference.

The meal plan page gets a **Send this week to Google Calendar** button.
Press it and the week's meals appear on a calendar the family already
uses — breakfast at 7am, lunch at noon, dinner at 6pm, an hour each,
marked *free* so nobody shows as busy. Each event's description carries
the servings, the ingredient list with amounts, and a link back to the
recipe.

**On the Windows desktop app** the button works the same way, signed in as you, and does not use
service accounts: see "Google Calendar" in [WINDOWS.md](WINDOWS.md). It starts its own sign-in and
event records and takes nothing over from this server, so don't send the same week from both to one
calendar: each meal would land on it twice. Its Sign out doesn't revoke access; only a
confirmed "Revoke access at Google" does. Its events have no link back to a recipe. An event deleted
by hand comes back the next time its meal changes, under the same id.

## It only ever writes

This is not a sync, on purpose. The calendar belongs to the family: they
add dentist appointments, drag things around and delete what they like,
and none of that comes back into the meal planner. Nothing on the
calendar can change a recipe or the plan.

The app records the id of every event it creates, and will only ever
touch those. Your own entries are untouchable by construction, not by
filtering carefully.

The button means *make Google match my plan for this week*:

| In the planner | On the calendar |
|---|---|
| a meal added | a new event |
| a meal changed | that same event, updated in place |
| a meal removed | that event deleted |
| nothing changed | nothing at all — no API calls |

Press it twice in a row and the second press does nothing. Delete one of
its events by hand and the next push adds the current meal back as a new
event rather than resurrecting the old one.

## Two ways to sign in

Both need a project at [console.cloud.google.com](https://console.cloud.google.com)
with the **Google Calendar API** enabled. There is no "log in with
Google" that skips the console. What differs is what the app can reach —
and, in practice, whether Google will let you finish the setup at all.

### As a service account — `python set_gcal.py` (the practical one)

A robot account you share the calendar with, exactly as you would with a
person. **No OAuth consent screen is involved at all**, which is what
makes this the realistic option for a household: nothing to publish, no
verification, and none of the homepage / privacy policy / terms of
service that Google demands before an External app can leave "Testing".

It also starts with access to **nothing** and only ever reaches the one
calendar you shared. If the key leaks, the damage is one calendar and you
revoke it by deleting the key — your Google account is untouched.

The cost is cosmetic: events show a `…iam.gserviceaccount.com` address as
their creator rather than your name.

Create a **service account**, download a **JSON key**, then share the
calendar with the service account's email address with permission **Make
changes to events**.

### As yourself — `python set_gcal_oauth.py`

Events show **you** as their creator, and nothing has to be shared with
anything.

**Read this before starting.** Two things make this impractical for a
personal project, and neither is obvious from the setup screens:

* Google's calendar scope is not per-calendar. Signing in as yourself
  lets this app reach **every calendar you own**, not only the one you
  picked.
* An External app issues refresh tokens that **expire after seven days**
  while its publishing status is "Testing" — so the link breaks weekly.
  Escaping that means publishing the app, and Google will not let you
  publish without a homepage domain, a published privacy policy and
  terms of service. A household has none of those. "Internal" removes
  the requirement but exists only for Google Workspace organisations,
  not personal Gmail accounts.

So this path suits you only if you already run a domain with those pages
published, or you are willing to re-run the script every week. Otherwise
use the service account above.

Set up an **OAuth client ID** of type *Desktop app*. The script opens
your browser, you click Allow once, and it offers a list of the calendars
you can write to. You will see an "unverified app" warning — continue
through *Advanced*.

### Which

Use the service account unless you already have a domain with a published
privacy policy and terms of service. It is not only the smaller
permission — it is the one that can actually be set up without publishing
an app to the world.

Either way the credential is written to the app's own secrets file
(`paths.env_path()`) on the machine running the app — never into the
repository, and it never needs to be sent anywhere. Running
`set_gcal_oauth.py` clears any service-account setting and vice versa, so
only one is ever live.

## Turning it off

Delete the `MEAL_PLANNER_GCAL_*` lines from the secrets file and restart
the app; the button disappears. Events already on the calendar stay
there — removing them is the calendar's business, not the app's.

## When something goes wrong

The button reports the problem rather than failing silently.

| It says | Usually means |
|---|---|
| `No Google calendar is linked yet` | the app was started before `set_gcal.py` / `set_gcal_oauth.py` was run, or without the secrets file |
| `invalid_grant` | the refresh token was revoked, or the consent screen is still in "Testing" and seven days passed — run `set_gcal_oauth.py` again |
| `403` on a push | for a service account, the calendar is not shared with it, or not with "Make changes to events" |
| `could not reach Google` | no internet, or Google is down; nothing was changed, press it again later |
