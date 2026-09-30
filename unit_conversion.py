"""Exact (never floating-point) amount parsing/formatting, unit
normalization, and conversion to a single US-customary (imperial) system
of measurement, for cooking quantities."""
from fractions import Fraction


def parse_amount(text) -> Fraction | None:
    if text is None:
        return None
    text = str(text).strip()
    if not text:
        return None

    parts = text.split()
    if len(parts) == 1:
        try:
            return Fraction(parts[0])
        except (ValueError, ZeroDivisionError):
            return None
    if len(parts) == 2 and "/" in parts[1]:
        try:
            return Fraction(parts[0]) + Fraction(parts[1])
        except (ValueError, ZeroDivisionError):
            return None
    return None


def format_amount(value: Fraction) -> str:
    whole, remainder = divmod(value.numerator, value.denominator)
    if remainder == 0:
        return str(whole)
    frac_str = f"{remainder}/{value.denominator}"
    return f"{whole} {frac_str}" if whole else frac_str


def scale_amount_text(amount_text, ratio: Fraction):
    """Multiply a written amount by a ratio, leaving unparseable text alone.

    An ingredient with no amount ("Salt, to taste") has nothing to scale, so
    it comes back untouched rather than becoming "0".
    """
    parsed = parse_amount(amount_text)
    if parsed is None:
        return amount_text
    return format_amount(parsed * ratio)


def servings_ratio(planned, base) -> Fraction:
    """Multiplier that turns amounts written for `base` servings into `planned`.

    Falls back to 1 (leave amounts alone) whenever either side is missing or
    unparseable, so a recipe with no recorded yield simply never scales.
    """
    planned_value = parse_amount(planned)
    base_value = parse_amount(base)
    if planned_value is None or base_value is None:
        return Fraction(1)
    if planned_value <= 0 or base_value <= 0:
        return Fraction(1)
    return planned_value / base_value


_UNIT_ALIASES = {
    "tsp": "tsp", "ts": "tsp", "teaspoon": "tsp", "teaspoons": "tsp",
    "tbsp": "tbsp", "tb": "tbsp", "tbs": "tbsp", "tablespoon": "tbsp", "tablespoons": "tbsp",
    "fl oz": "fl oz", "floz": "fl oz", "fluid ounce": "fl oz", "fluid ounces": "fl oz",
    "cup": "cup", "cups": "cup", "c": "cup",
    "pt": "pt", "pint": "pt", "pints": "pt",
    "qt": "qt", "quart": "qt", "quarts": "qt",
    "gal": "gal", "ga": "gal", "gallon": "gal", "gallons": "gal",
    "oz": "oz", "ounce": "oz", "ounces": "oz",
    "lb": "lb", "lbs": "lb", "pound": "lb", "pounds": "lb",
    "ml": "ml", "milliliter": "ml", "milliliters": "ml", "millilitre": "ml", "millilitres": "ml",
    "l": "l", "liter": "l", "liters": "l", "litre": "l", "litres": "l",
    "g": "g", "gram": "g", "grams": "g",
    "kg": "kg", "kilogram": "kg", "kilograms": "kg", "kilo": "kg", "kilos": "kg",
    "each": "each", "ea": "each",
    "clove": "clove", "cl": "clove", "cloves": "clove",
    "slice": "slice", "sl": "slice", "slices": "slice",
    "can": "can", "cn": "can", "cans": "can",
    "package": "package", "pk": "package", "pkg": "package", "packages": "package",
    "pinch": "pinch", "pn": "pinch", "pinches": "pinch",
    "dash": "dash", "dr": "dash", "dashes": "dash",
    "small": "small", "sm": "small",
    "medium": "medium", "md": "medium",
    "large": "large", "lg": "large",
}


def normalize_unit(text) -> str:
    cleaned = str(text).strip().lower() if text is not None else ""
    return _UNIT_ALIASES.get(cleaned, cleaned)


def is_known_unit_word(word) -> bool:
    return str(word).strip().lower() in _UNIT_ALIASES


_VOLUME_FACTORS = {"tsp": 1, "tbsp": 3, "fl oz": 6, "cup": 48, "pt": 96, "qt": 192, "gal": 768}
_WEIGHT_FACTORS = {"oz": 1, "lb": 16}

# Units that take a trailing "s" plural. All other measurable units stay invariant.
_UNITS_WITH_S_PLURAL = {"cup"}


def unit_category(unit: str) -> str | None:
    if unit in _VOLUME_FACTORS:
        return "volume"
    if unit in _WEIGHT_FACTORS:
        return "weight"
    return None


def _factors_for(category: str) -> dict:
    return _VOLUME_FACTORS if category == "volume" else _WEIGHT_FACTORS


def to_base(category: str, unit: str, amount: Fraction) -> Fraction:
    return amount * _factors_for(category)[unit]


def best_unit(category: str, base_amount: Fraction) -> tuple[str, Fraction]:
    factors = _factors_for(category)
    for unit, factor in sorted(factors.items(), key=lambda kv: kv[1], reverse=True):
        if base_amount >= factor:
            return unit, base_amount / factor
    smallest_unit = min(factors, key=factors.get)
    return smallest_unit, base_amount / factors[smallest_unit]


# (category, factor to convert one unit of this metric unit into that
# category's base unit) -- standard cooking-chart approximations, chosen so
# every ratio above stays a clean integer:
# 1 tsp = 5 ml, so 1 ml = 1/5 tsp; 1 l = 1000 ml = 200 tsp.
# 1 oz = 28 g, so 1 g = 1/28 oz; 1 kg = 1000 g = 1000/28 oz.
_METRIC_TO_BASE = {
    "ml": ("volume", Fraction(1, 5)),
    "l": ("volume", Fraction(200)),
    "g": ("weight", Fraction(1, 28)),
    "kg": ("weight", Fraction(1000, 28)),
}


def to_imperial(amount_text, unit_text) -> tuple:
    amount = parse_amount(amount_text)
    if amount is None:
        return amount_text, unit_text

    unit = normalize_unit(unit_text)
    if unit not in _METRIC_TO_BASE:
        return amount_text, unit_text

    category, per_unit_factor = _METRIC_TO_BASE[unit]
    base_amount = amount * per_unit_factor
    display_unit, display_amount = best_unit(category, base_amount)
    return format_amount(display_amount), display_unit


def combine_lines(rows: list) -> list:
    """rows: (name, amount_text, unit_text) triples. Returns the same
    shape, with matching ingredients combined: same ingredient name
    (case-insensitive; first-seen casing wins for display), grouped by
    unit category (or, for non-measurable units, the exact normalized
    unit) -- amounts that don't parse are never merged into anything."""
    groups = {}
    order = []
    for name, amount_text, unit_text in rows:
        key = name.lower()
        if key not in groups:
            groups[key] = {"display_name": name, "buckets": {}}
            order.append(key)
        unit = normalize_unit(unit_text)
        category = unit_category(unit)
        bucket_key = category if category else unit
        groups[key]["buckets"].setdefault(bucket_key, []).append(
            (amount_text, unit_text, unit, category)
        )

    result = []
    for key in order:
        group = groups[key]
        name = group["display_name"]
        for entries in group["buckets"].values():
            parsed_entries = [
                (parse_amount(amount_text), amount_text, unit_text, unit, category)
                for amount_text, unit_text, unit, category in entries
            ]
            parseable = [e for e in parsed_entries if e[0] is not None]
            unparseable = [e for e in parsed_entries if e[0] is None]

            seen_unparseable = set()
            for _, amount_text, unit_text, _, _ in unparseable:
                dedup_key = (amount_text, unit_text)
                if dedup_key in seen_unparseable:
                    continue
                seen_unparseable.add(dedup_key)
                result.append((name, amount_text, unit_text))

            if not parseable:
                continue
            if len(parseable) == 1:
                _, amount_text, unit_text, _, _ = parseable[0]
                result.append((name, amount_text, unit_text))
                continue

            category = parseable[0][4]
            if category:
                units_used = {e[3] for e in parseable}
                if len(units_used) == 1:
                    # All entries already share one normalized unit -- sum
                    # directly rather than round-tripping through
                    # to_base/best_unit, which would over-eagerly promote
                    # to the next larger unit (e.g. 3 cups -> 1 1/2 pt)
                    # whenever the total crosses that unit's threshold.
                    unit = next(iter(units_used))
                    total = sum(e[0] for e in parseable)
                    display_unit = unit if total == 1 or unit not in _UNITS_WITH_S_PLURAL else unit + "s"
                    result.append((name, format_amount(total), display_unit))
                else:
                    base_total = sum(to_base(category, e[3], e[0]) for e in parseable)
                    display_unit, display_amount = best_unit(category, base_total)
                    result.append((name, format_amount(display_amount), display_unit))
            else:
                total = sum(e[0] for e in parseable)
                unit_text = parseable[0][2]
                result.append((name, format_amount(total), unit_text))
    return result
