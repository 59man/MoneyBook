#!/usr/bin/env python3
"""Drive the app on a device over adb by on-screen text (needs `source ~/android-toolchain/env.sh`).

Usage: tools/adb_ui.py <serial> <cmd> [arg] [<cmd> [arg] ...]
  text <t>      tap the first node whose text is exactly <t>
  last <t>      tap the last such node (e.g. a dialog button that repeats the title)
  contains <t>  tap the first node whose text contains <t> (uiautomator XML-escapes quotes: &quot;)
  desc <d>      tap the node with content description <d>
  back | sleep <s> | list (print all texts) | shot <name> (reference/shots/<name>.png, gitignored)
Some Compose nodes (e.g. auto-sized grid labels) do not show in the dump; tap those by coordinates.
"""
import subprocess, sys, time, re, os
from PIL import Image
S = os.path.join("reference", "shots")  # screenshots hold personal data; reference/ is gitignored
serial = sys.argv[1]
def adb(*a, out=False):
    r = subprocess.run(["adb", "-s", serial, *a], capture_output=True)
    return r.stdout if out else None
def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("exec-out", "cat", "/sdcard/ui.xml", out=True).decode("utf-8", "replace")
def find(attr, value, contains=False, last=False):
    for _ in range(4):
        x = dump()
        nodes = list(re.finditer(r'<node [^>]*>', x))
        for m in (reversed(nodes) if last else nodes):
            node = m.group(0)
            v = re.search(r'%s="([^"]*)"' % attr, node)
            if v and (value in v.group(1) if contains else v.group(1) == value):
                b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups()
                x1, y1, x2, y2 = map(int, b)
                return (x1 + x2) // 2, (y1 + y2) // 2
        time.sleep(1)
    return None
args = sys.argv[2:]
while args:
    cmd = args.pop(0)
    if cmd in ("text", "desc", "contains", "last"):
        val = args.pop(0)
        pos = find("content-desc" if cmd == "desc" else "text", val, contains=(cmd == "contains"), last=(cmd == "last"))
        if pos is None: print("NOT FOUND:", cmd, val); sys.exit(1)
        adb("shell", "input", "tap", str(pos[0]), str(pos[1])); time.sleep(1.3)
    elif cmd == "back":
        adb("shell", "input", "keyevent", "BACK"); time.sleep(1)
    elif cmd == "shot":
        name = args.pop(0); os.makedirs(S, exist_ok=True); p = os.path.join(S, name + ".png")
        open(p, "wb").write(adb("exec-out", "screencap", "-p", out=True))
        im = Image.open(p); im.resize((im.width // 3, im.height // 3)).save(os.path.join(S, name + "s.png")); print("shot", name)
    elif cmd == "list":
        x = dump(); print([t for t in re.findall(r'text="([^"]+)"', x)])
    elif cmd == "sleep":
        time.sleep(float(args.pop(0)))
