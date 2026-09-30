"""Convert a browser-extension-extracted recipe (Schema.org JSON-LD
fields, already normalized to plain strings by the extension) into
Open Recipe Format, mirroring meal_master.py's style: pure logic, no
Flask dependency, independently unit-testable."""
import re
import urllib.request
from urllib.parse import urlparse

import recipe_sync
import unit_conversion

_UNICODE_FRACTIONS = {
    "¼": "1/4", "½": "1/2", "¾": "3/4",
    "⅓": "1/3", "⅔": "2/3",
    "⅕": "1/5", "⅖": "2/5", "⅗": "3/5", "⅘": "4/5",
    "⅙": "1/6", "⅚": "5/6",
    "⅛": "1/8", "⅜": "3/8", "⅝": "5/8", "⅞": "7/8",
}

_AMOUNT_RE = re.compile(
    r"^((?:\d+\s+)?\d+(?:[/.]\d+)?)(?:\s*(?:-|to)\s*\d+(?:[/.]\d+)?)?\s*(.*)$"
)
_PAREN_RE = re.compile(r"^\(([^)]*)\)\s*(.*)$")
_FRACTION_CHARS = "".join(_UNICODE_FRACTIONS.keys())
_SPACED_MIXED_FRACTION_RE = re.compile(
    rf"^(\d+)\s+([{_FRACTION_CHARS}])(.*)$"
)
_TRAILING_UNIT_PUNCT_RE = re.compile(r"[.,;]+$")


def _unit_candidate(phrase: str) -> tuple[str, bool]:
    """Strips trailing punctuation from a candidate unit word/phrase so
    real-world JSON-LD like "tbsp." or "cups," (followed by a trailing
    note) still matches the known-unit lookup. Returns the stripped
    text plus whether a comma was among the stripped characters, so the
    caller can preserve it for the later comma-based note-splitting."""
    stripped = _TRAILING_UNIT_PUNCT_RE.sub("", phrase)
    return stripped, "," in phrase[len(stripped):]


def _rebuild_remainder(rest: str, had_comma: bool) -> str:
    if not had_comma:
        return rest
    return f", {rest}" if rest else ","


def _normalize_leading_fraction(text: str) -> str:
    for frac_char, frac_text in _UNICODE_FRACTIONS.items():
        if text.startswith(frac_char):
            return frac_text + text[len(frac_char):]
    # Spaced mixed number, e.g. "1 ½ cups flour" -> "1 1/2 cups flour".
    # (The glued form "1½" is out of scope, same as the original design.)
    mixed_match = _SPACED_MIXED_FRACTION_RE.match(text)
    if mixed_match:
        whole, frac_char, rest = mixed_match.groups()
        return f"{whole} {_UNICODE_FRACTIONS[frac_char]}{rest}"
    return text


def parse_ingredient_line(line: str) -> dict:
    text = _normalize_leading_fraction(line.strip())
    if not text:
        return {"name": "", "amount": "", "unit": "", "notes": None}

    match = _AMOUNT_RE.match(text)
    if not match:
        return {"name": text, "amount": "", "unit": "", "notes": None}

    amount = match.group(1)
    remainder = match.group(2).strip()

    notes = []
    paren_match = _PAREN_RE.match(remainder)
    if paren_match:
        notes.append(paren_match.group(1).strip())
        remainder = paren_match.group(2).strip()

    unit = ""
    tokens = remainder.split()
    if len(tokens) >= 2:
        candidate, had_comma = _unit_candidate(f"{tokens[0]} {tokens[1]}")
        if unit_conversion.is_known_unit_word(candidate):
            unit = candidate
            remainder = _rebuild_remainder(" ".join(tokens[2:]), had_comma)
    if not unit and tokens:
        candidate, had_comma = _unit_candidate(tokens[0])
        if unit_conversion.is_known_unit_word(candidate):
            unit = candidate
            remainder = _rebuild_remainder(" ".join(tokens[1:]), had_comma)

    # A parenthetical can also follow the unit, e.g. "1 can (15 oz) black
    # beans" -- check again now that the unit has been stripped off.
    paren_match = _PAREN_RE.match(remainder)
    if paren_match:
        notes.append(paren_match.group(1).strip())
        remainder = paren_match.group(2).strip()

    if "," in remainder:
        name, trailing_note = remainder.split(",", 1)
        trailing_note = trailing_note.strip()
        if trailing_note:
            notes.append(trailing_note)
    else:
        name = remainder

    return {
        "name": name.strip(),
        "amount": amount,
        "unit": unit,
        "notes": notes or None,
    }


def domain_from_url(url) -> str:
    if not url:
        return ""
    return urlparse(url).netloc


_MAX_IMAGE_BYTES = 20 * 1024 * 1024


def fetch_image_bytes(url: str, timeout: int = 10) -> bytes:
    """Downloads bytes from `url`. Raises on any network failure (bad
    URL, timeout, non-2xx status, etc.), on a URL scheme other than
    http/https (this URL comes from parsing an untrusted web page, so
    e.g. file:// must never be followed), or on a response larger than
    _MAX_IMAGE_BYTES -- the caller decides whether to skip the image
    rather than fail the whole import."""
    if urlparse(url).scheme.lower() not in ("http", "https"):
        raise ValueError(f"Unsupported image URL scheme: {url!r}")

    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        data = response.read(_MAX_IMAGE_BYTES + 1)
    if len(data) > _MAX_IMAGE_BYTES:
        raise ValueError(f"Image response exceeded {_MAX_IMAGE_BYTES} bytes")
    return data


def build_recipe_data(payload: dict, recipe_uuid: str) -> dict:
    """Builds an ORF-shaped dict (ready for yaml.safe_dump) from a
    validated extension payload -- caller has already checked `name`/
    `ingredients`/`steps` are present and non-empty. Never sets `image`;
    the caller adds that after downloading/processing the photo, if
    any."""
    ingredients = []
    for line in payload["ingredients"]:
        parsed = parse_ingredient_line(line)
        preserved = {"notes": parsed["notes"]} if parsed["notes"] else None
        ingredients.append(
            recipe_sync.build_new_ingredient(
                parsed["name"], parsed["amount"], parsed["unit"], None, preserved
            )
        )

    data = {
        "recipe_uuid": recipe_uuid,
        "recipe_name": str(payload["name"]).strip(),
        "category": "None",
        "subcategory": "None",
        "ingredients": ingredients,
        "steps": [{"step": s} for s in payload["steps"]],
    }

    yields = recipe_sync.parse_yield_text(payload.get("yield_text"))
    if yields:
        data["yields"] = [yields]

    author = str(payload.get("author") or "").strip()
    if author:
        data["author"] = author

    source_url = str(payload.get("source_url") or "").strip()
    if source_url:
        data["source_url"] = source_url

    return data
