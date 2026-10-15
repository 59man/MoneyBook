#!/usr/bin/env python3
"""Calibration: list horizontal bands (dividers, gaps, ink rows) in a column strip of a screenshot.

Usage: tools/rows.py <img.png> <x0> <x1> [y0] [y1]
"""
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert("RGB")
x0, x1 = int(sys.argv[2]), int(sys.argv[3])
y0 = int(sys.argv[4]) if len(sys.argv) > 4 else 0
y1 = int(sys.argv[5]) if len(sys.argv) > 5 else im.height
px = im.load()
def rowsig(y):
    cols = [px[x, y] for x in range(x0, x1)]
    return cols
prev = None; start = None
out = []
for y in range(y0, y1):
    cols = rowsig(y)
    bg = max(set(cols), key=cols.count)
    ink = sum(1 for c in cols if sum(abs(c[i]-bg[i]) for i in range(3)) > 60)
    uniform = ink == 0
    key = (bg, uniform)
    if key != prev:
        if prev is not None: out.append((start, y-1, prev))
        prev = key; start = y
out.append((start, y1-1, prev))
for s, e, (bg, u) in out:
    print(f"{s:5d}-{e:5d} h={e-s+1:4d} bg={bg} {'blank' if u else 'INK'}")
