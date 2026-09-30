# recipes/

Drop Open Recipe Format `.yaml` files here — one recipe per file.

Reference: https://open-recipe-format.readthedocs.io/en/latest/

After adding, editing, or removing files, click "Rescan recipes/ folder"
in the app (or restart it — it syncs automatically on startup).

Only files ending in `.yaml` are indexed — `.yml` is not recognized and
will be silently ignored.

Start the app with `python app.py`, not `flask run`. The initial folder
sync happens in `app.py`'s `if __name__ == "__main__":` block, not inside
`create_app()`, so `flask run` will serve an empty (or stale) recipe list
until you trigger a manual "Rescan recipes/ folder".

Always quote `usda_num` values, e.g. `usda_num: '02047'` rather than
`usda_num: 02047`. Unquoted, YAML 1.1 (which PyYAML uses) parses a
leading-zero number as octal when every digit is 0-7 — `02047` becomes
the integer `1063`, silently corrupting the value. Values containing an
8 or 9 happen to fall back to a plain string and are unaffected, but
don't rely on that — quote every `usda_num`.

## Recipe identity and renaming files

Recipes are identified by a `recipe_uuid` in the file, not by filename —
renaming a `.yaml` file is safe and preserves everything tied to it
(including calendar assignments). If a file has no `recipe_uuid` (or an
empty/`None` one), sync generates one and writes it back into the file
automatically, as a single-line edit that leaves the rest of the file
untouched.

If two files ever end up with the same `recipe_uuid` (e.g. one was
copy-pasted from another without changing it), sync reports it as an
error instead of corrupting either recipe. Visit `/sync/conflicts` in
the app to resolve it — each conflicting file can be deleted or given a
fresh UUID.

## Importing Meal Master (.mmf) files

The Recipes page's upload form also accepts legacy Meal Master (`.mmf`)
files, in addition to native `.yaml`/`.yml` uploads. Each recipe in the
file is converted to Open Recipe Format and added to the library
(recipes whose title already matches one in your library are skipped,
not overwritten).

Ingredient line parsing uses a leading-whitespace + known-unit-vocabulary
heuristic rather than fixed-column offsets, since real-world Meal Master
files aren't reliably fixed-column. A `=== NAME ===`-style banner used to
separate a recipe into named subsections (e.g. "BECHAMEL SAUCE",
"BOLOGNESE SAUCE") is recognized during import: it's never added as an
ingredient itself, and every ingredient that follows it gets a `section`
key set to the banner's (title-cased) name, all the way through to the
recipe detail page, which groups ingredients under their section
headings. Hand-authored `.yaml` recipes can set the same `section:` key
on any ingredient directly.

A rarer decorative divider style, `-= -- Ingredients =-`, is NOT
recognized (Meal Master's line-continuation syntax consumes it before
section detection ever sees it) and can still occasionally merge into
the name of an adjacent ingredient in the generated recipe. This is a
known, accepted quirk of the source format — similar in spirit to the
`usda_num` octal-parsing quirk above — not a bug, and can be fixed by
hand-editing the generated `.yaml` file if it bothers you.
