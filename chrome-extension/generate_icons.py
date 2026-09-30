"""One-off script to (re)generate the extension's toolbar icons. Run
with `python chrome-extension/generate_icons.py` from the repo root;
the output PNGs are committed, this does not run at extension load
time."""
from pathlib import Path

from PIL import Image, ImageDraw

ICON_DIR = Path(__file__).parent / "icons"
BACKGROUND = (11, 97, 105)  # matches the app's steel-teal accent (DESIGN.md)
FOREGROUND = (255, 255, 255)


def _draw_icon(size: int) -> Image.Image:
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    margin = max(1, size // 12)
    radius = max(2, size // 5)
    draw.rounded_rectangle(
        (margin, margin, size - margin, size - margin), radius=radius, fill=BACKGROUND
    )

    fork_width = max(1, size // 10)
    top = size * 0.24
    bottom = size * 0.76
    fork_x = size * 0.36
    draw.line((fork_x, top, fork_x, bottom), fill=FOREGROUND, width=fork_width)
    tine_gap = size * 0.09
    for offset in (-tine_gap, 0, tine_gap):
        draw.line(
            (fork_x + offset, top, fork_x + offset, top + size * 0.22),
            fill=FOREGROUND,
            width=max(1, fork_width // 2),
        )

    knife_x = size * 0.64
    draw.line((knife_x, top, knife_x, bottom), fill=FOREGROUND, width=fork_width)
    draw.polygon(
        [
            (knife_x - fork_width, top),
            (knife_x + fork_width, top),
            (knife_x, top + size * 0.24),
        ],
        fill=FOREGROUND,
    )
    return image


def main() -> None:
    ICON_DIR.mkdir(parents=True, exist_ok=True)
    for size in (16, 48, 128):
        _draw_icon(size).save(ICON_DIR / f"icon{size}.png")
    print(f"Wrote icon16.png, icon48.png, icon128.png to {ICON_DIR}")


if __name__ == "__main__":
    main()
