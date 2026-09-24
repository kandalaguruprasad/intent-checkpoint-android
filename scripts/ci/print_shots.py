#!/usr/bin/env python3
"""Print downscaled screenshots as base64 JPEG between markers, so they can be reviewed from the
job log where artifact downloads aren't available. usage: print_shots.py <dir> <batch> <batches>"""
import base64
import io
import sys
from pathlib import Path

from PIL import Image

src, batch, batches = Path(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
files = sorted(src.glob("*.png"))
for f in files[batch::batches]:
    img = Image.open(f).convert("RGB")
    img.thumbnail((300, 1000))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=55, optimize=True)
    b64 = base64.b64encode(buf.getvalue()).decode()
    print(f"=====BEGIN {f.stem}=====")
    for i in range(0, len(b64), 900):
        print(b64[i : i + 900])
    print(f"=====END {f.stem}=====")
