#!/usr/bin/env python3
"""Calibration: compare ink bounding boxes of named regions between a Realbyte capture and a MoneyBook screenshot.

Usage: tools/measure.py <ref.png> <mb.png> <spec> ; spec lines: "name x0 y0 x1 y1 [| mx0 my0 mx1 my1]".
Prints each box in both images plus dy/dyb (top/bottom offset) and w% (width ratio; <100 = MoneyBook text too big).
"""
import sys
from collections import Counter
from PIL import Image
A = Image.open(sys.argv[1]).convert("RGB"); B = Image.open(sys.argv[2]).convert("RGB")
def bbox(im, x0, y0, x1, y1, thr=90):
    reg = im.crop((x0, y0, x1, y1)); px = reg.load()
    bg = Counter(reg.getdata()).most_common(1)[0][0]
    xs = []; ys = []
    for y in range(reg.height):
        for x in range(reg.width):
            c = px[x, y]
            if sum(abs(c[i] - bg[i]) for i in range(3)) > thr: xs.append(x); ys.append(y)
    if not xs: return None
    return (x0 + min(xs), y0 + min(ys), x0 + max(xs), y0 + max(ys))
for line in open(sys.argv[3]):
    line = line.strip()
    if not line or line.startswith("#"): continue
    name, rest = line.split(None, 1)
    parts = rest.split("|")
    r = list(map(int, parts[0].split())); m = list(map(int, parts[1].split())) if len(parts) > 1 else r
    a = bbox(A, *r); b = bbox(B, *m)
    if not a or not b: print(f"{name:12s} ref={a} mb={b}"); continue
    wa, ha = a[2]-a[0]+1, a[3]-a[1]+1; wb, hb = b[2]-b[0]+1, b[3]-b[1]+1
    print(f"{name:12s} REF x{a[0]:4d}-{a[2]:4d} y{a[1]:4d}-{a[3]:4d} w{wa:4d} h{ha:3d} | MB x{b[0]:4d}-{b[2]:4d} y{b[1]:4d}-{b[3]:4d} w{wb:4d} h{hb:3d} | dx{b[0]-a[0]:+4d} dy{b[1]-a[1]:+4d} dyb{b[3]-a[3]:+4d} w%{100*wb/wa:4.0f}")
