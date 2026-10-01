"""Checks that the Pi parses the Android app's backup export exactly as it
parses the recipes that went into it (apart from the recipe_uuid the phone
assigns). Run after the Android unit tests:

    python -m tests.check_android_export android/app/build/compat/export.zip
"""
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import recipe_sync  # noqa: E402

ORF_CASES = ROOT / "tests" / "fixtures" / "parity" / "orf.json"


def _key(parsed: dict) -> str:
    return json.dumps({k: v for k, v in parsed.items() if k != "recipe_uuid"}, sort_keys=True)


def main(zip_path: str) -> int:
    originals = sorted(
        _key(case["expected"]) for case in json.loads(ORF_CASES.read_text(encoding="utf-8"))
        if "error" not in case
    )
    exported = []
    with zipfile.ZipFile(zip_path) as archive:
        for name in archive.namelist():
            if name.startswith("recipes/") and name.endswith(".yaml"):
                exported.append(_key(recipe_sync.parse_recipe_yaml(archive.read(name).decode("utf-8"))))
    if sorted(exported) != originals:
        print(f"MISMATCH: {len(exported)} exported vs {len(originals)} original recipes differ when parsed by the Pi")
        return 1
    print(f"OK: the Pi parses all {len(exported)} exported recipes exactly as the originals")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
