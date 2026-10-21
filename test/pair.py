"""Paired comparison of two versions on the same games: each map played from both sides, with the same seed.

Usage: python3 -I test/pair.py <tag A> <tag B>
Reads the STATS line of build/match/<tag>-<map>-<mode>-<n>.log (xtest.Match prints it at the end): the side played, the
winner and when, each side's first fighting unit, and the armies at 5, 10 and 15 minutes (count/strength). A game is
matched with the other version's game of the same map and side. Prints, for each version, games won, lost and drawn and
the measures; for the pairs, how often B did better, worse or the same (a win before a draw before a loss; of two wins
the sooner, of two losses the later) and the chance of so lopsided a split by luck alone (two-sided sign test).
"""
import glob
import math
import re
import statistics
import sys

STATS = re.compile(r"^STATS (\S+) side (\S) winner=(\S+) at (\d+):(\d+); first unit us (\S+) them (\S+);(.*)$")


def seconds(text):
    if text == "never":
        return None
    m, s = text.split(":")
    return int(m) * 60 + int(s)


def games(tag):
    out = {}
    for path in glob.glob("build/match/%s-*.log" % tag):
        with open(path, errors="replace") as f:
            for line in f:
                m = STATS.match(line.strip())
                if not m:
                    continue
                name, side, winner, mm, ss, us, them, rest = m.groups()
                armies = {}
                for a in re.finditer(r"at (\d+) us (\d+)/([\d.]+) them (\d+)/([\d.]+)", rest):
                    armies[int(a.group(1))] = (int(a.group(2)), float(a.group(3)), int(a.group(4)), float(a.group(5)))
                out[(name, side)] = {
                    "result": 1 if winner == "us" else -1 if winner in ("Brain", "enemy") else 0,
                    "at": int(mm) * 60 + int(ss), "us": seconds(us), "them": seconds(them), "armies": armies,
                }
    return out


def rank(g):
    # a win sooner is better, a loss later is better
    return (g["result"], -g["at"] if g["result"] > 0 else g["at"] if g["result"] < 0 else 0)


def summary(name, gs):
    w = sum(1 for g in gs if g["result"] > 0)
    l = sum(1 for g in gs if g["result"] < 0)
    d = len(gs) - w - l
    firsts = [g["us"] for g in gs if g["us"] is not None]
    theirs = [g["them"] for g in gs if g["them"] is not None]
    none = sum(1 for g in gs if g["us"] is None)
    a10 = [g["armies"][10][1] - g["armies"][10][3] for g in gs if 10 in g["armies"]]
    print("%-4s %3d games: won %3d lost %3d drew %3d (%.0f%% won); first unit median %s (none %d), theirs %s; "
          "army at 10 min, ours less theirs, mean %s" % (
              name, len(gs), w, l, d, 100.0 * w / max(1, len(gs)),
              clock(statistics.median(firsts)) if firsts else "-", none,
              clock(statistics.median(theirs)) if theirs else "-",
              "%.0f over %d" % (statistics.mean(a10), len(a10)) if a10 else "-"))


def clock(s):
    return "%d:%02d" % (int(s) // 60, int(s) % 60)


def sign_p(k, n):
    # two-sided: the chance of k or more of n (or as few) going one way when each is even
    if n == 0:
        return 1.0
    k = max(k, n - k)
    tail = sum(math.comb(n, i) for i in range(k, n + 1)) / 2.0 ** n
    return min(1.0, 2 * tail)


def main():
    a, b = sys.argv[1], sys.argv[2]
    ga, gb = games(a), games(b)
    keys = sorted(set(ga) & set(gb))
    summary(a, [ga[k] for k in keys])
    summary(b, [gb[k] for k in keys])
    better = worse = same = 0
    flips = []
    for k in keys:
        ra, rb = rank(ga[k]), rank(gb[k])
        if ga[k]["result"] != gb[k]["result"]:
            flips.append("%s/%s %s->%s" % (k[0], k[1], "WDL"[1 - ga[k]["result"]], "WDL"[1 - gb[k]["result"]]))
        if rb > ra:
            better += 1
        elif rb < ra:
            worse += 1
        else:
            same += 1
    print("pairs %d (only in %s: %d, only in %s: %d): %s better %d, worse %d, same %d; sign test p = %.3f" % (
        len(keys), a, len(set(ga) - set(gb)), b, len(set(gb) - set(ga)), b, better, worse, same, sign_p(better, better + worse)))
    up = sum(1 for k in keys if gb[k]["result"] > ga[k]["result"])
    down = sum(1 for k in keys if gb[k]["result"] < ga[k]["result"])
    print("outcome changed in %d pairs: up %d, down %d (sign test p = %.3f)" % (up + down, up, down, sign_p(up, up + down)))
    if flips:
        print("  " + "; ".join(flips))


if __name__ == "__main__":
    main()
