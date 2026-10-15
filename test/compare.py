"""Compares batches of campaign runs by what each run is measured by.

Usage: python3 -I test/compare.py <batch name> [<batch name> ...]
Reads build/logs/<name>-<n>.log, the METRICS line each run prints, and gives for every batch the share of
runs won, lost and not won in time, and the mean of each measure with its standard error. With two batches
it says for each measure whether they differ by more than twice the standard error of the difference.
"""
import glob
import math
import os
import re
import sys

MEASURES = ["minute", "lost", "income10", "income30", "research30", "research60", "made", "destroyed"]


def runs(name):
    out = []
    for path in sorted(glob.glob(os.path.join("build", "logs", name + "-*.log"))):
        if not re.search(re.escape(name) + r"-\d+\.log$", path):
            continue
        with open(path, errors="replace") as f:
            for line in f:
                if "METRICS" in line:
                    fields = dict(part.split("=", 1) for part in line.split()[1:] if "=" in part)
                    out.append(fields)
    return out


def mean_se(values):
    n = len(values)
    if n == 0:
        return float("nan"), float("nan")
    m = sum(values) / n
    if n < 2:
        return m, float("nan")
    var = sum((v - m) ** 2 for v in values) / (n - 1)
    return m, math.sqrt(var / n)


def share(rows, result):
    n = len(rows)
    k = sum(1 for r in rows if r.get("result") == result)
    p = k / n if n else float("nan")
    return k, p, math.sqrt(p * (1 - p) / n) if n else float("nan")


def main(names):
    table = {}
    for name in names:
        rows = runs(name)
        table[name] = rows
        print(f"== {name}: {len(rows)} runs" + (f" ({rows[0].get('sector')})" if rows else ""))
        for result in ("won", "lost", "timeout"):
            k, p, se = share(rows, result)
            print(f"   {result:8s} {k:3d}  {p * 100:5.1f}% ± {se * 100:4.1f}")
        for measure in MEASURES:
            values = [float(r[measure]) for r in rows if measure in r]
            m, se = mean_se(values)
            print(f"   {measure:10s} {m:9.1f} ± {se:6.1f}")
    if len(names) == 2:
        a, b = (table[n] for n in names)
        print(f"== {names[1]} against {names[0]} (* where the difference is more than twice its standard error)")
        for result in ("won", "lost"):
            _, pa, sa = share(a, result)
            _, pb, sb = share(b, result)
            d, s = pb - pa, math.sqrt(sa ** 2 + sb ** 2)
            print(f"   {result:10s} {d * 100:+7.1f}%  {'*' if s > 0 and abs(d) > 2 * s else ''}")
        for measure in MEASURES:
            ma, sa = mean_se([float(r[measure]) for r in a if measure in r])
            mb, sb = mean_se([float(r[measure]) for r in b if measure in r])
            d, s = mb - ma, math.sqrt(sa ** 2 + sb ** 2)
            print(f"   {measure:10s} {d:+9.1f}  {'*' if s > 0 and abs(d) > 2 * s else ''}")


if __name__ == "__main__":
    main(sys.argv[1:])
