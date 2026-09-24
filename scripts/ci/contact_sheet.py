#!/usr/bin/env python3
"""Tile all screenshots into one labelled contact sheet. usage: contact_sheet.py <dir> <out.jpg>"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

src, out = Path(sys.argv[1]), sys.argv[2]
files = sorted(src.glob("*.png"))
if not files:
    sys.exit(0)
W, H, PAD, COLS = 360, 800, 12, 6
rows = (len(files) + COLS - 1) // COLS
sheet = Image.new("RGB", (COLS * (W + PAD) + PAD, rows * (H + 40 + PAD) + PAD), "white")
draw = ImageDraw.Draw(sheet)
for i, f in enumerate(files):
    img = Image.open(f).convert("RGB")
    img.thumbnail((W, H))
    x = PAD + (i % COLS) * (W + PAD)
    y = PAD + (i // COLS) * (H + 40 + PAD)
    sheet.paste(img, (x, y + 40))
    draw.text((x, y + 10), f.stem, fill="black")
sheet.save(out, "JPEG", quality=80)
