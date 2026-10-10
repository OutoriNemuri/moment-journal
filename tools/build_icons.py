#!/usr/bin/env python3
"""
Generate the app's launcher icon PNGs from the official Material Symbols
`book` glyph.

Why this file exists
--------------------
Android cannot reference a web-font glyph from an icon XML, so launchers
below API 26 need real raster assets. Generating them from the authoritative
glyph keeps the low-API and high-API icons visually identical, instead of
hand-drawing a lookalike.

Source choice
-------------
Two CDN endpoints serve a glyph called `book`:

  * materialiconsoutlined/book/v1/24px.svg
        viewBox="0 0 24 24"  -- ordinary top-down grid, bookmark points DOWN.
  * materialsymbolsoutlined/book/default/24px.svg
        viewBox="0 -960 960 960" -- negative-up grid, AND the served file has
        the bookmark pointing UP, i.e. vertically mirrored relative to the
        correct drawing.

The first one is used here. It is the same glyph shape that the Compose
material-icons-extended library renders, and it needs no coordinate
gymnastics -- an earlier attempt with the negative-up source produced a
visibly upside-down icon.

Usage:  python3 tools/build_icons.py
"""

import re
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"

GLYPH_URL = (
    "https://fonts.gstatic.com/s/i/materialiconsoutlined/book/v1/24px.svg"
)

# Standard top-down 24x24 grid: x and y both run 0 -> 24.
GRID = 24.0

LEGACY_DP = 48       # classic launcher icon
FOREGROUND_DP = 108  # adaptive-icon foreground layer

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

BRAND_INK = "#4A6350"


def fetch_glyph_path() -> str:
    with urllib.request.urlopen(GLYPH_URL, timeout=60) as r:
        svg = r.read().decode("utf-8")
    # The file contains a transparent bounding square first; take the path
    # that actually draws the glyph (the one with fill omitted / not "none").
    paths = re.findall(r'<path([^>]*?)d="([^"]+)"', svg)
    drawn = [d for attrs, d in paths if 'fill="none"' not in attrs]
    if not drawn:
        raise SystemExit(f"no drawn <path> found in {GLYPH_URL}")
    return drawn[0]


def wrap_svg(size_px: float, path_data: str, *, scale: float,
             bg: str | None, ink: str) -> str:
    """Centre the 24-unit glyph on a `size_px` canvas.

    Plain top-down grid, so the transform is a pure translate + uniform
    scale -- no mirroring anywhere:
        point (x, y) -> (tx + s*x, ty + s*y)
        x, y in [0, 24] -> [tx, tx + 24s] and [ty, ty + 24s]
    Centring both axes therefore needs tx == ty == (size - 24s) / 2.
    """
    s = size_px / GRID * scale
    side = GRID * s
    offset = (size_px - side) / 2.0
    bg_rect = (
        f'<rect width="{size_px:.3f}" height="{size_px:.3f}" fill="{bg}"/>'
        if bg else ""
    )
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" '
        f'width="{size_px:.3f}" height="{size_px:.3f}" '
        f'viewBox="0 0 {size_px:.3f} {size_px:.3f}">'
        f'{bg_rect}'
        f'<g transform="translate({offset:.3f},{offset:.3f}) '
        f'scale({s:.6f},{s:.6f})">'
        f'<path d="{path_data}" fill="{ink}"/>'
        f"</g></svg>"
    )


def render(svg_text: str, out: Path, px: int) -> None:
    tmp = out.with_suffix(".tmp.svg")
    tmp.write_text(svg_text, encoding="utf-8")
    subprocess.run(
        ["rsvg-convert", "-w", str(px), "-h", str(px), "-o", str(out), str(tmp)],
        check=True,
    )
    tmp.unlink()


def main() -> int:
    try:
        path_data = fetch_glyph_path()
    except Exception as e:
        print(f"FATAL: could not fetch glyph: {e}", file=sys.stderr)
        return 1
    print(f"fetched glyph ({len(path_data)} chars) from {GLYPH_URL}")

    written = []

    # 1) legacy launcher icon: opaque brand-green tile with the glyph inset,
    #    so it survives a circular mask on old launchers.
    for bucket, factor in DENSITIES.items():
        px = int(round(LEGACY_DP * factor))
        d = RES / f"mipmap-{bucket}"
        d.mkdir(parents=True, exist_ok=True)
        svg = wrap_svg(px, path_data, scale=0.62, bg=BRAND_INK, ink="#FFFFFF")
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            out = d / name
            render(svg, out, px)
            written.append(out)

    # 2) adaptive foreground: transparent, glyph inside the safe zone.
    for bucket, factor in DENSITIES.items():
        px = int(round(FOREGROUND_DP * factor))
        d = RES / f"mipmap-{bucket}"
        svg = wrap_svg(px, path_data, scale=0.48, bg=None, ink="#FFFFFF")
        out = d / "ic_launcher_foreground.png"
        render(svg, out, px)
        written.append(out)

    for p in sorted(written):
        print(f"  {p.relative_to(ROOT)}  {p.stat().st_size} B")
    print(f"\n{len(written)} files written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
