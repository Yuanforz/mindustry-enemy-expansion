#!/bin/sh
# The audits of a batch's games added up (Match prints one at the end of each game): where the core unit's time went,
# how machines ran, what they starved of, and how far stages fell behind their model. Usage: test/audit.sh TAG [MODE]
cd "$(dirname "$0")/.."
ls build/match/"$1"-*-"${2:-*}"-*.log >/dev/null 2>&1 || { echo "no logs for $1"; exit 1; }
cat build/match/"$1"-*-"${2:-*}"-*.log | python3 -c '
import re, sys, collections
spent = collections.Counter(); starved = collections.Counter(); states = collections.Counter(); games = 0
late = []; never = 0; stages = 0
for line in sys.stdin:
    if line.startswith("  audit: the core unit"):
        games += 1
        head, _, rest = line.partition("machines\x27 time:")
        for name, sec in re.findall(r"([a-z][a-z \-]*?) (\d+) s;", head.split("time:", 1)[1]): spent[name.strip()] += int(sec)
        mt, _, st = rest.partition("starved of:")
        for name, pct in re.findall(r"([a-z ]+?) (\d+)%", mt): states[name.strip()] += int(pct)
        for name, sec in re.findall(r"([a-z\-]+) (\d+) s", st): starved[name] += int(sec)
    m = re.match(r"  audit: stage \d+ \((\d+) jobs\): the model had it all up by (\S+), the game (\S+)", line)
    if m:
        stages += 1
        def sec(t):
            if t in ("never", "not"): return None
            a, b = t.split(":"); return int(a) * 60 + int(b)
        mod, game = sec(m.group(2)), sec(m.group(3))
        if game is None: never += 1
        elif mod and mod < 3600: late.append(game / max(mod, 1))
total = sum(spent.values()) or 1
print(f"{games} games")
print("core unit:", ", ".join(f"{k} {100 * v / total:.0f}%" for k, v in spent.most_common(8)))
print("machines (mean of games):", ", ".join(f"{k} {v / max(games, 1):.0f}%" for k, v in states.most_common()))
print("starved of (machine-seconds):", ", ".join(f"{k} {v}" for k, v in starved.most_common(8)))
late.sort()
if late: print(f"stages: {stages}, not finished {never}; game/model time of those finished: median {late[len(late) // 2]:.2f}, 80th pct {late[int(len(late) * 0.8)]:.2f}")
'
