"""Settings for the Alexa-hosted "chef" skill. Copy this file into the
skill's Code tab as `config.py` next to `lambda_function.py`, then fill
in both values. Never commit the filled-in copy anywhere.

BASE_URL: the Home Assistant remote URL from Settings -> Home Assistant
Cloud -> Remote control, with no trailing path.

LONG_LIVED_ACCESS_TOKEN: created in Home Assistant under your profile ->
Security -> Long-lived access tokens. It grants that user's access to
Home Assistant, so it lives in the hosted skill only; revoke it from the
same page if it ever leaks.
"""

BASE_URL = "https://<your-nabu-casa-remote-url>"
LONG_LIVED_ACCESS_TOKEN = "<paste token>"
