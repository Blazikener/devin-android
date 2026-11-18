#!/usr/bin/env python3
"""Frame raw 1080x2400 emulator screenshots in a phone shell and compose README images.

Usage: python3 tools/frame_screenshots.py RAW_DIR OUT_DIR
RAW_DIR holds {light,dark}_{home,sessions,chat,create}.png (adb exec-out screencap -p).
Requires Pillow.
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

SCREENS = ["home", "sessions", "chat", "create"]
BEZEL = 34
SCREEN_RADIUS = 96
SHADOW = 60


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def frame(shot):
    w, h = shot.size
    fw, fh = w + 2 * BEZEL, h + 2 * BEZEL
    outer = SCREEN_RADIUS + BEZEL
    canvas = Image.new("RGBA", (fw + 2 * SHADOW, fh + 2 * SHADOW), (0, 0, 0, 0))

    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        (SHADOW, SHADOW + 24, SHADOW + fw, SHADOW + fh + 24), outer, fill=(0, 0, 0, 90)
    )
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(28)))

    body = Image.new("RGBA", (fw, fh), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)
    d.rounded_rectangle((0, 0, fw - 1, fh - 1), outer, fill=(58, 58, 62, 255))
    d.rounded_rectangle((5, 5, fw - 6, fh - 6), outer - 5, fill=(14, 14, 16, 255))
    screen = shot.convert("RGBA")
    body.paste(screen, (BEZEL, BEZEL), rounded_mask(screen.size, SCREEN_RADIUS))
    cx, cy, r = fw // 2, BEZEL + 46, 17
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(8, 8, 10, 255))
    d.ellipse((cx - 6, cy - 6, cx + 2, cy + 2), fill=(30, 34, 44, 255))
    canvas.alpha_composite(body, (SHADOW, SHADOW))
    return canvas


def scaled(img, width):
    return img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)


def row(phones, gap):
    w = sum(p.width for p in phones) + gap * (len(phones) - 1)
    h = max(p.height for p in phones)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    x = 0
    for p in phones:
        out.alpha_composite(p, (x, (h - p.height) // 2))
        x += p.width + gap
    return out


def main(raw, out):
    out.mkdir(parents=True, exist_ok=True)
    framed = {}
    for theme in ("light", "dark"):
        for name in SCREENS:
            img = frame(Image.open(raw / f"{theme}_{name}.png"))
            framed[theme, name] = img
            scaled(img, 420).save(out / f"{theme}-{name}.png", optimize=True)
        hero = [scaled(framed[theme, n], 560) for n in ("home", "sessions", "chat")]
        hero[1] = scaled(framed[theme, "sessions"], 620)
        row(hero, -20).save(out / f"hero-{theme}.png", optimize=True)


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
