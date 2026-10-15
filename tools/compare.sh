#!/usr/bin/env bash
# Usage: tools/compare.sh <reference-name> [output-name] [adb-serial]
# Screenshots the phone and writes reference/compare/<output>.png: Realbyte capture left, MoneyBook right.
# Output stays in gitignored reference/ because screens contain personal data.
set -euo pipefail
ref="reference/screens/$1.png"
out="reference/compare/${2:-$1}.png"
serial="${3:-}"
[ -f "$ref" ] || { echo "missing $ref" >&2; exit 1; }
mkdir -p reference/compare
tmp="$(mktemp --suffix .png)"
adb ${serial:+-s "$serial"} exec-out screencap -p > "$tmp"
python3 - "$ref" "$tmp" "$out" <<'PY'
import sys
from PIL import Image
a = Image.open(sys.argv[1]).convert("RGB")
b = Image.open(sys.argv[2]).convert("RGB")
canvas = Image.new("RGB", (a.width + b.width + 20, max(a.height, b.height)), "white")
canvas.paste(a, (0, 0))
canvas.paste(b, (a.width + 20, 0))
canvas.save(sys.argv[3])
print(sys.argv[3])
PY
rm -f "$tmp"
