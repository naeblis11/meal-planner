"""Voice-command logic for the Alexa skill: turning what Alexa heard into
the app's own values (fraction amounts, known aisles, meal slots, dates),
matching a spoken recipe name to the library, and composing the sentence
Alexa says back. Pure functions -- no Flask, no database -- so every
branch is unit-testable. The routes in app.py glue this to the modules
that actually write rows."""
import difflib
import re
from dataclasses import dataclass, field
from datetime import date
from fractions import Fraction

import grocery_categories
import meal_calendar
import unit_conversion

# Answers to "How much?" / "Which aisle?" that mean "leave it blank".
# The Alexa interaction model lists these as synonyms of a "skip" value,
# so the canonical value that arrives is usually "skip"; the rest are
# here in case a raw utterance slips through.
SKIP_WORDS = frozenset({
    "skip", "none", "not sure", "no", "nothing", "don't know", "dont know",
    "no amount", "no idea", "i don't know",
})

_NUMBER_WORDS = {
    "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
    "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11,
    "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15,
    "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19,
    "twenty": 20, "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60,
    "seventy": 70, "eighty": 80, "ninety": 90,
    "a": 1, "an": 1, "a couple": 2, "couple": 2, "a pair": 2,
    "half": Fraction(1, 2), "a half": Fraction(1, 2), "one half": Fraction(1, 2),
    "dozen": 12, "a dozen": 12,
}


def given(value) -> str | None:
    """The trimmed text, or None when Alexa sent nothing usable: blank, or
    one of the words that mean "skip this"."""
    text = (value or "").strip()
    if not text or text.lower() in SKIP_WORDS:
        return None
    return text


# Sentence tails Alexa sometimes folds into the item slot when the
# utterance is a near-miss of a sample ("add milk to cart" -> "milk to
# cart"). Stripped from the end of an item name only.
_DESTINATION_TAIL = re.compile(
    r"(?:^|\s+)(?:to|in|on|into|onto)\s+(?:the\s+|my\s+)?(?:shopping\s+)?(?:cart|list|pantry)$",
    re.IGNORECASE,
)


def item_name(value) -> str | None:
    """The item the user named, or None. Like given(), but also drops a
    trailing "to the cart" / "in the pantry" that leaked into the slot."""
    text = given(value)
    if text is None:
        return None
    return given(_DESTINATION_TAIL.sub("", text))


def normalize_quantity(text) -> str | None:
    """Alexa's quantity ("2", "1.5", "two", "a dozen", "half") as the app's
    fraction text ("2", "1 1/2", "12", "1/2"), or None when it isn't a
    quantity at all."""
    text = given(text)
    if text is None:
        return None
    lowered = text.lower()
    if lowered in _NUMBER_WORDS:
        return unit_conversion.format_amount(Fraction(_NUMBER_WORDS[lowered]))
    try:
        value = Fraction(lowered)
    except (ValueError, ZeroDivisionError):
        return None
    return unit_conversion.format_amount(value)


def normalize_unit(text, quantity) -> str | None:
    """The app's unit spelling for a spoken unit word ("pounds" -> "lb").
    Unknown words ("bags") are kept as said, lowercased, the way recipes
    already store them. A unit with no quantity means nothing, so it is
    dropped."""
    text = given(text)
    if text is None or quantity is None:
        return None
    return unit_conversion.normalize_unit(text)


def _fold_aisle(text: str) -> str:
    """Lowercase, "&" and "and" made interchangeable, whitespace collapsed
    -- so the Alexa slot's spoken-safe "Dairy and Eggs" matches the app's
    "Dairy & Eggs" spelling in grocery_categories.AISLE_ORDER."""
    folded = text.lower().replace("&", "and")
    return " ".join(folded.split())


def normalize_aisle(text) -> str | None:
    """One of the app's aisle names, matched case-insensitively with
    "&"/"and" folded, else None."""
    text = given(text)
    if text is None:
        return None
    folded = _fold_aisle(text)
    for aisle in grocery_categories.AISLE_ORDER:
        if _fold_aisle(aisle) == folded:
            return aisle
    return None


def normalize_meal(text) -> str:
    """One of the calendar's slots; anything unrecognised means Dinner,
    the meal people plan by voice far more than the other two."""
    text = given(text)
    if text is not None:
        for slot in meal_calendar.SLOTS:
            if slot.lower() == text.lower():
                return slot
    return "Dinner"


def parse_day(text, today: date) -> date | None:
    """The calendar day Alexa resolved ("2026-09-17"); blank means today.
    AMAZON.DATE can also send a week ("2026-W38"), a weekend
    ("2026-W38-WE"), a month, a year, or a season -- none of which is a
    day the calendar can plan, so those come back None."""
    text = given(text)
    if text is None:
        return today
    try:
        parsed = date.fromisoformat(text)
    except ValueError:
        return None
    return parsed if parsed.isoformat() == text else None


FUZZY_MIN_RATIO = 0.6
FUZZY_TIE_MARGIN = 0.05
MAX_CANDIDATES = 3


@dataclass
class MatchResult:
    status: str                  # "found" | "none" | "ambiguous"
    name: str | None = None
    candidates: list = field(default_factory=list)


def _normalise_name(text: str) -> str:
    text = re.sub(r"[^a-z0-9 ]+", " ", text.lower())
    return " ".join(text.split())


def match_recipe(spoken: str, names: list) -> MatchResult:
    """Which library recipe the user meant. Exact (normalised) match wins;
    then containment either way ("tacos" in "Fish Tacos"), shortest name
    first because it's closest to what was said; then difflib similarity
    with a floor. Two contenders too close to call come back as
    "ambiguous" with their names, so Alexa can ask instead of guessing."""
    wanted = _normalise_name(spoken)
    if not wanted or not names:
        return MatchResult("none")
    normalised = [(name, _normalise_name(name)) for name in names]

    exact = [name for name, norm in normalised if norm == wanted]
    if exact:
        return MatchResult("found", exact[0])

    contained = [name for name, norm in normalised if wanted in norm or norm in wanted]
    if contained:
        contained.sort(key=lambda name: (len(name), name))
        if len(contained) > 1 and len(contained[0]) == len(contained[1]):
            tied = [name for name in contained if len(name) == len(contained[0])]
            return MatchResult("ambiguous", candidates=tied[:MAX_CANDIDATES])
        return MatchResult("found", contained[0])

    scored = sorted(
        ((difflib.SequenceMatcher(None, wanted, norm).ratio(), name) for name, norm in normalised),
        reverse=True,
    )
    best_ratio, best_name = scored[0]
    if best_ratio < FUZZY_MIN_RATIO:
        return MatchResult("none")
    close = [name for ratio, name in scored if best_ratio - ratio <= FUZZY_TIE_MARGIN]
    if len(close) > 1:
        return MatchResult("ambiguous", candidates=close[:MAX_CANDIDATES])
    return MatchResult("found", best_name)


SPEECH_NOTHING_HEARD = "I didn't catch what to add."
SPEECH_NEED_A_DAY = "I need a specific day, like Thursday."
SPEECH_NOT_SET_UP = "The meal planner's voice API isn't set up yet."
SPEECH_REFUSED = "The meal planner refused the request. Check the token in Home Assistant."
SPEECH_UNREACHABLE = "I couldn't reach the meal planner."  # spoken by Home Assistant, kept here for the docs test

# How the app's unit abbreviations should be read aloud.
_SPOKEN_UNITS = {
    "tsp": "teaspoon", "tbsp": "tablespoon", "fl oz": "fluid ounce", "cup": "cup",
    "pt": "pint", "qt": "quart", "gal": "gallon", "oz": "ounce", "lb": "pound",
    "ml": "milliliter", "l": "liter", "g": "gram", "kg": "kilogram",
    "clove": "clove", "slice": "slice", "can": "can", "package": "package",
    "pinch": "pinch", "dash": "dash",
}


def spoken_amount(amount, unit) -> str:
    """'2 gallons', '1 pound', '1 1/2 cups', '2 bags', '3', or '' when
    there is no amount. Only the app's own units are pluralised; a word
    the user chose ('bags') is read back as they said it."""
    if not amount:
        return ""
    if not unit:
        return amount
    word = _SPOKEN_UNITS.get(unit)
    if word is None:
        return f"{amount} {unit}"
    if amount != "1":
        word += "s"
    return f"{amount} {word}"


def spoken_day(d: date) -> str:
    return f"{d.strftime('%A, %B')} {d.day}"


def _sentence_case(name: str) -> str:
    return name[:1].upper() + name[1:]


def shopping_speech(status, name, amount, unit, aisle) -> str:
    if status == "duplicate":
        return f"{_sentence_case(name)} is already on your shopping list."
    if status == "merged":
        return f"{_sentence_case(name)} was already on your shopping list; it's now {spoken_amount(amount, unit)}."
    what = f"{spoken_amount(amount, unit)} of {name}" if amount else name
    where = f", under {aisle}" if aisle else ""
    return f"Added {what} to your shopping list{where}."


def pantry_speech(status, name) -> str:
    if status == "duplicate":
        return f"{_sentence_case(name)} is already in your pantry."
    if status == "restored":
        return f"Put {name} back in your pantry."
    return f"Added {name} to your pantry."


def meal_speech(recipe, slot, d: date, previous) -> str:
    when = f"{slot.lower()} on {spoken_day(d)}"
    if previous == recipe:
        return f"{recipe} is already planned for {when}."
    replacing = f", replacing {previous}" if previous else ""
    return f"Added {recipe} for {when}{replacing}."


def no_match_speech(spoken) -> str:
    return f"I couldn't find a recipe like '{spoken}'."


def ambiguous_speech(candidates) -> str:
    if len(candidates) == 2:
        listed = f"{candidates[0]} and {candidates[1]}"
    else:
        listed = ", ".join(candidates[:-1]) + f", and {candidates[-1]}"
    return f"I found {listed}. Which one?"
