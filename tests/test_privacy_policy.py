"""The privacy policy: one text, two copies, and two rules it must keep.

`PrivacyPolicy.kt` is what the app shows; `docs/PRIVACY.md` is what gets
published. A policy that says one thing in the app and another on the web is
worse than having none, so this keeps them word for word the same.

The two rules are the owner's (2026-10-12) and are easy to undo by accident
while editing prose:

* **Say what happens, never how.** The policy must not describe where the app
  is built, hosted or deployed, or name the parts it is made of. It still has
  to name a third party that genuinely sees something -- that is the point of
  a privacy policy -- so the ban is on plumbing, not on disclosure.
* **Nothing personal.** No names, accounts, handles or machine names: the only
  contact is the site.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "apps/shared/ui/src/commonMain/kotlin/com/naeblis11/mealplanner/settings/PrivacyPolicy.kt"
MARKDOWN = ROOT / "docs/PRIVACY.md"

# Plumbing: how this particular app is put together and shipped. Naming any of
# it in a policy tells a reader nothing about their data and tells everyone
# else how the thing is built.
IMPLEMENTATION_TERMS = [
    "github", "sqlite", "room", "ktor", "kotlin", "compose", "jpackage", "msi", "apk",
    "localappdata", "appdata", "documents folder", "dpapi", "mdns", "port ", "localhost",
    "127.0.0.1", "tailscale", "raspberry", "flask", "python", "home assistant", "chrome extension",
    "oauth", "calendar.events", "calendarlist", "json", "yaml", "database", "repository",
    "server", "endpoint", "api key", "token",
]

# Personal: who the owner is, what their machines are called, where the source lives.
# Personal, in general terms. The owner's own names and machines are deliberately not spelled out here, so this
# file can go to the public repo: the export's scan (tools/banned-terms.txt, tests/test_export_public.py) already
# fails on any of them anywhere in the exported tree, the policy's two copies included.
PERSONAL_PATTERNS = [
    r"naeblis",
    r"mealpi",
    r"\bgithub\.com/",
    r"[\w.+-]+@[\w-]+\.[\w.]+",  # any email address
]


def kotlin_source():
    return KOTLIN.read_text(encoding="utf-8")


def kotlin_strings():
    """Every double-quoted literal in PrivacyPolicy.kt, concatenations joined.

    The file is a list of `"..." + "..."` pieces; joining adjacent literals back
    together is enough to compare against the published paragraphs.
    """
    source = kotlin_source()
    body = source[source.index("object PrivacyPolicy"):]
    # Drop KDoc and line comments so their wording is never compared.
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    body = re.sub(r"(?m)^\s*//.*$", "", body)
    out, buffer = [], []
    for line in body.splitlines():
        pieces = re.findall(r'"((?:[^"\\]|\\.)*)"', line)
        if not pieces:
            if buffer:
                out.append("".join(buffer))
                buffer = []
            continue
        buffer.extend(pieces)
        if not line.rstrip().endswith("+"):
            out.append("".join(buffer))
            buffer = []
    if buffer:
        out.append("".join(buffer))
    return [piece.replace('\\"', '"') for piece in out if piece.strip()]


def markdown_paragraphs():
    text = MARKDOWN.read_text(encoding="utf-8")
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    return [block.strip() for block in text.split("\n\n") if block.strip()]


class SameInBothPlaces(unittest.TestCase):
    """What the app shows and what gets published must be the same words."""

    def test_every_in_app_paragraph_is_published(self):
        published = "\n\n".join(markdown_paragraphs())
        missing = [piece for piece in kotlin_strings() if piece not in published]
        self.assertEqual(
            missing, [],
            "these are in the app but not in docs/PRIVACY.md -- copy them across:\n  "
            + "\n  ".join(missing),
        )

    def test_every_published_paragraph_is_in_the_app(self):
        shown = "\n".join(kotlin_strings())
        stray = []
        for block in markdown_paragraphs():
            if block.startswith("#"):
                heading = block.lstrip("# ").strip()
                if heading in ("Meal Planner privacy policy",) or heading in shown:
                    continue
                stray.append(heading)
            elif block not in shown:
                stray.append(block)
        self.assertEqual(
            stray, [],
            "these are published but the app never shows them -- add them to PrivacyPolicy.kt:\n  "
            + "\n  ".join(stray),
        )

    def test_the_date_matches(self):
        self.assertIn(
            next(piece for piece in kotlin_strings() if piece.startswith("Last updated")),
            MARKDOWN.read_text(encoding="utf-8"),
        )


class SaysWhatNeverHow(unittest.TestCase):
    def test_no_implementation_detail(self):
        for where, text in (("the app", " ".join(kotlin_strings())),
                            ("docs/PRIVACY.md", "\n\n".join(markdown_paragraphs()))):
            lowered = text.lower()
            for term in IMPLEMENTATION_TERMS:
                with self.subTest(where=where, term=term.strip()):
                    self.assertNotIn(
                        term, lowered,
                        f"{where} names '{term.strip()}'. The policy says what happens to the "
                        "reader's data, not how the app is built or shipped.",
                    )

    def test_it_still_names_the_third_parties_that_see_something(self):
        """Vague about plumbing must never become vague about disclosure."""
        shown = " ".join(kotlin_strings())
        for required in ("Google API Services User Data Policy", "Limited Use",
                         "Google Play services"):
            with self.subTest(required=required):
                self.assertIn(required, shown)


class NothingPersonal(unittest.TestCase):
    def test_no_personal_identifiers(self):
        # The policy's own words, not the Kotlin around them: the file's package
        # declaration carries the app's identifier, which no reader ever sees.
        for where, text in (("the app", " ".join(kotlin_strings())),
                            ("docs/PRIVACY.md", MARKDOWN.read_text(encoding="utf-8"))):
            for pattern in PERSONAL_PATTERNS:
                with self.subTest(where=where, pattern=pattern):
                    self.assertIsNone(
                        re.search(pattern, text, re.I),
                        f"{where} matches /{pattern}/. The policy carries no one's name, "
                        "account or machine -- the only contact is the site.",
                    )

    def test_the_maker_and_the_only_contact(self):
        shown = " ".join(kotlin_strings())
        self.assertIn("VibeForgeDev", shown)
        self.assertIn("vibeforgedev.com", shown)


class ShownInTheApp(unittest.TestCase):
    """A published policy nobody can read in the app is only half of the job."""

    def test_settings_shows_the_policy(self):
        settings = (ROOT / "apps/shared/ui/src/commonMain/kotlin/com/naeblis11/mealplanner"
                    / "settings/SettingsScreen.kt").read_text(encoding="utf-8")
        self.assertIn("PRIVACY_PANEL", settings)
        self.assertIn("PrivacyPolicy.SECTIONS", settings)
        self.assertIn("PrivacyPolicy.SUMMARY", settings)


if __name__ == "__main__":
    unittest.main()
