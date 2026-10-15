#!/usr/bin/env python3
"""Copy a code block out of a plan, so executing a plan never retypes its code.

Usage: tools/planblock.py <plan.md> <marker> [out-file]
Prints (or writes to out-file) the first fenced block after the first line containing <marker>,
with the fence's indentation removed.
"""
import sys
plan = open(sys.argv[1]).read().split('\n')
marker = sys.argv[2]
i = next(n for n, l in enumerate(plan) if marker in l)
while not plan[i].lstrip().startswith('```'): i += 1
indent = len(plan[i]) - len(plan[i].lstrip())
j = i + 1
while not (plan[j].strip() == '```' and len(plan[j]) - len(plan[j].lstrip()) == indent): j += 1
body = '\n'.join(l[indent:] if l.startswith(' ' * indent) else l for l in plan[i + 1:j]) + '\n'
if len(sys.argv) > 3:
    import os; os.makedirs(os.path.dirname(sys.argv[3]) or '.', exist_ok=True); open(sys.argv[3], 'w').write(body); print('wrote', sys.argv[3], body.count('\n'), 'lines')
else:
    sys.stdout.write(body)
