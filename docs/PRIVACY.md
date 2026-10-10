# Meal Planner privacy policy

Meal Planner is a free recipe, meal-plan, pantry and shopping-list app for Windows and Android. It
has no servers, no accounts and no analytics. Nothing you put in it is sent to the people who make it.

## What stays on your devices

Your recipes, photos, meal plans, pantry and shopping list are stored only on your own PC (your
Documents folder and your user's local app data) and on your phone (the app's private storage), and
in any backup files you choose to export. Android's own cloud backup of the app is turned off.

## Google Calendar (optional)

If you choose **Sign in with Google** under Settings > Google Calendar, you sign in to your own Google account on
Google's own page, and Meal Planner asks for two permissions:

- `calendar.calendarlist.readonly`: to list your calendars, so you can pick the one meals go to.
- `calendar.events`: to add, change and remove the meal events Meal Planner itself created in the
  calendar you picked.

Meal Planner reads no other calendar data and never changes events it did not create. The sign-in
is kept only on your PC, encrypted with Windows' own data protection for your Windows user. Nothing
from your Google account goes anywhere but between your PC and Google.

You can stop this at any time: Settings > Google Calendar > Sign out forgets the sign-in on the PC,
and you can remove Meal Planner's access at <https://myaccount.google.com/permissions>.

Meal Planner's use of information received from Google APIs adheres to the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including the Limited Use requirements.

## Update checks

At most once a day when it opens, and when you press **Check for updates**, each app asks GitHub
(github.com and GitHub's download hosts, over https) whether there is a newer version, and downloads
it when you press **Install**. It sends nothing about you or your data.

## On your home network

The Windows app answers the Meal Planner Chrome extension on your own PC, and, if you set it up,
Home Assistant on your network. It announces itself on your local network so a second Meal Planner
PC in the same home can see it. None of this leaves your network.

## Contact

Questions: open an issue at <https://github.com/naeblis11/meal-planner/issues>.
