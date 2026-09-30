"""Convert Meal Master (.mmf) recipe text into Open Recipe Format YAML."""
import re
from dataclasses import dataclass, field

import yaml

import recipe_sync

_RECIPE_START = re.compile(r"^MMMMM-+", re.MULTILINE)

_UNITS = {
    "ts", "tb", "c", "oz", "lb", "pt", "qt", "ga", "ml", "l", "g", "kg",
    "ea", "x", "sm", "md", "lg", "pk", "cn", "sl", "cl", "dr", "pn", "fl",
}

_AMOUNT_RE = re.compile(r"^((?:\d+\s+)?\d+(?:/\d+)?)\s*(.*)$")
_SECTION_HEADER_RE = re.compile(r"^=+\s*(.+?)\s*=+$")


@dataclass
class ParseResult:
    recipes: list = field(default_factory=list)
    errors: list = field(default_factory=list)


def _split_chunks(text: str) -> list:
    positions = [m.start() for m in _RECIPE_START.finditer(text)]
    if not positions:
        return []
    positions.append(len(text))
    return [text[positions[i]:positions[i + 1]] for i in range(len(positions) - 1)]


def _chunk_identifier(chunk: str, index: int) -> str:
    for line in chunk.splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("MMMMM"):
            return stripped[:60]
    return f"recipe #{index + 1}"


def _find_field_line_index(lines: list, label: str) -> int:
    for idx, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith(label) and stripped[len(label):].strip():
            return idx
    return -1


def _find_field(lines: list, label: str) -> str:
    idx = _find_field_line_index(lines, label)
    if idx < 0:
        return ""
    return lines[idx].strip()[len(label):].strip()


def _extract_ingredient_lines(lines: list, title_idx: int):
    i = title_idx + 1
    while i < len(lines):
        stripped = lines[i].strip()
        if stripped == "" or stripped.startswith("Categories:") or stripped.startswith("Yield:"):
            i += 1
            continue
        break

    ingredient_lines = []
    while i < len(lines):
        line = lines[i]
        if line.strip() == "":
            i += 1
            continue
        if not line[:1].isspace():
            break
        ingredient_lines.append(line)
        i += 1
    return ingredient_lines, i


def _join_continuations(ingredient_lines: list) -> list:
    joined = []
    for line in ingredient_lines:
        stripped = line.strip()
        if stripped.startswith("-"):
            continuation_text = stripped[1:].lstrip()
            if joined:
                joined[-1] += continuation_text
            continue
        joined.append(stripped)
    return joined


def _parse_ingredient_line(line: str) -> dict:
    match = _AMOUNT_RE.match(line)
    if match:
        amount = match.group(1)
        remainder = match.group(2)
    else:
        amount = ""
        remainder = line

    tokens = remainder.split(None, 1)
    unit = ""
    if tokens and tokens[0].lower() in _UNITS:
        unit = tokens[0]
        remainder = tokens[1] if len(tokens) > 1 else ""

    if " -- " in remainder:
        name, note = remainder.split(" -- ", 1)
    else:
        name, note = remainder, ""

    name = name.strip()
    ingredient = {"amounts": [{"amount": amount, "unit": unit}]}
    if note.strip():
        ingredient["notes"] = [note.strip()]
    return {name: ingredient}


def _parse_ingredients(ingredient_lines: list) -> list:
    joined = _join_continuations(ingredient_lines)
    ingredients = []
    current_section = None
    for line in joined:
        if not line:
            continue
        header_match = _SECTION_HEADER_RE.match(line)
        if header_match:
            current_section = header_match.group(1).strip().title()
            continue
        ingredient = _parse_ingredient_line(line)
        if current_section is not None:
            (_, body), = ingredient.items()
            body["section"] = current_section
        ingredients.append(ingredient)
    return ingredients


def _extract_instructions(lines: list, start: int):
    stop_markers = ("Source:", "Notes:", "Yield:")
    i = start
    body_lines = []
    while i < len(lines):
        stripped = lines[i].strip()
        if stripped in stop_markers or stripped.startswith('S("') or stripped.startswith("MMMMM"):
            break
        body_lines.append(lines[i])
        i += 1

    steps = []
    paragraph = []
    for line in body_lines:
        if line.strip() == "":
            if paragraph:
                steps.append({"step": " ".join(paragraph)})
                paragraph = []
            continue
        paragraph.append(line.strip())
    if paragraph:
        steps.append({"step": " ".join(paragraph)})
    return steps, i


def _read_quoted_block(lines: list, start: int):
    collected = []
    i = start
    while i < len(lines):
        line = lines[i]
        collected.append(line.strip())
        i += 1
        if line.rstrip().endswith('"'):
            break
    text = " ".join(part for part in collected if part)
    text = text.strip()
    if text.startswith('"'):
        text = text[1:]
    if text.endswith('"'):
        text = text[:-1]
    return text.strip(), i


def _parse_tail_fields(lines: list, start: int):
    source = ""
    real_yield_text = ""
    extra_notes = []
    cuisine = ""

    i = start
    while i < len(lines):
        stripped = lines[i].strip()
        if stripped == "Source:":
            text, i = _read_quoted_block(lines, i + 1)
            source = text
        elif stripped == "Notes:":
            text, i = _read_quoted_block(lines, i + 1)
            if text:
                extra_notes.append(text)
        elif stripped == "Yield:":
            text, i = _read_quoted_block(lines, i + 1)
            real_yield_text = text
        elif stripped.startswith('S("'):
            text, i = _read_quoted_block(lines, i + 1)
            cuisine = text
        else:
            i += 1
    return source, real_yield_text, extra_notes, cuisine


def _parse_yield(text: str):
    return recipe_sync.parse_yield_text(text)


def _parse_chunk(chunk: str) -> dict:
    lines = chunk.splitlines()

    title_idx = _find_field_line_index(lines, "Title:")
    if title_idx < 0:
        raise ValueError("Missing Title: line")
    title = lines[title_idx].strip()[len("Title:"):].strip()

    categories = _find_field(lines, "Categories:")
    header_yield_text = _find_field(lines, "Yield:")

    ingredient_lines, body_start = _extract_ingredient_lines(lines, title_idx)
    ingredients = _parse_ingredients(ingredient_lines)
    if not ingredients:
        raise ValueError("No ingredients found")

    steps, tail_start = _extract_instructions(lines, body_start)
    if not steps:
        raise ValueError("No instructions found")

    source, real_yield_text, extra_notes, cuisine = _parse_tail_fields(lines, tail_start)

    notes = []
    if source:
        notes.append(f"Source: {source}")
    notes.extend(extra_notes)
    if cuisine:
        notes.append(f"Cuisine: {cuisine}")

    yields = _parse_yield(real_yield_text) or _parse_yield(header_yield_text)

    data = {
        "recipe_uuid": "None",
        "recipe_name": title,
        "category": "None",
        "subcategory": categories if categories else "None",
        "ingredients": ingredients,
        "steps": steps,
    }
    if yields:
        data["yields"] = [yields]
    if notes:
        data["notes"] = notes
    return data


def parse_meal_master(text: str) -> ParseResult:
    result = ParseResult()
    chunks = _split_chunks(text)
    for i, chunk in enumerate(chunks):
        chunk_id = _chunk_identifier(chunk, i)
        try:
            parsed = _parse_chunk(chunk)
        except ValueError as exc:
            result.errors.append((chunk_id, str(exc)))
            continue
        yaml_text = yaml.safe_dump(parsed, sort_keys=False, allow_unicode=True)
        result.recipes.append({"title": parsed["recipe_name"], "yaml_text": yaml_text})
    return result
