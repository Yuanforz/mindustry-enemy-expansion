"""The scoreboard's table: one line per run of test/score.sh, every measure side by side.

Usage: python3 -I test/score.py <name> [<name> ...]
Reads build/score/<name>/{alone,raid,duel}/*.log. A measure that a run did not play is left blank.
  first unit   median minute:second of the first unit made, alone (all kinds), and how many maps had none in thirty minutes
  T1 / T2      units of the first tier and of the second made in thirty minutes alone, mean a map; maps with any T2
  raid         the AI's core lost / the raider's core taken, of 32, by a person's pace of raiding from the sixth minute
  duel         won / lost / neither against the baseline, both seats of every map
  score        one number to go by: duel (won - lost) + 2 x (raid taken - lost) + 4 x maps with T2 - 10 x minutes of first unit over 3
"""
import glob
import os
import re
import statistics
import sys

T2 = {'locus', 'precept', 'vanquish', 'conquer', 'cleroi', 'anthicus', 'tecta', 'collaris', 'avert', 'obviate', 'quell', 'disrupt'}


def clean(text):
    return re.sub(r'\x1b\[[0-9;]*m', '', text)


def alone(name):
    files = glob.glob(os.path.join('build', 'score', name, 'alone', '*.log'))
    if not files:
        return None
    firsts, t1, t2, with2 = [], [], [], 0
    for f in files:
        t = clean(open(f, errors='replace').read())
        m = re.search(r'  UNITS(.*)', t)
        if not m:
            continue
        first, n1, n2 = None, 0, 0
        for unit, mm, ss, made in re.findall(r' (\S+) first (\d+):(\d+) made (\d+);', m.group(1)):
            sec = int(mm) * 60 + int(ss)
            first = sec if first is None else min(first, sec)
            if unit in T2:
                n2 += int(made)
            else:
                n1 += int(made)
        firsts.append(first if first is not None else 9999)
        t1.append(n1)
        t2.append(n2)
        if n2 > 0:
            with2 += 1
    if not firsts:
        return None
    med = statistics.median(firsts)
    return dict(first=med, none=sum(1 for x in firsts if x >= 9999), t1=statistics.mean(t1), t2=statistics.mean(t2), with2=with2, n=len(firsts))


def raid(name):
    files = glob.glob(os.path.join('build', 'score', name, 'raid', '*.log'))
    if not files:
        return None
    lost = taken = n = 0
    for f in files:
        m = re.search(r'RAID (.*)', clean(open(f, errors='replace').read()))
        if not m:
            continue
        n += 1
        lost += 'ENEMY FELL' in m.group(1)
        taken += 'PLAYER FELL' in m.group(1)
    return dict(lost=lost, taken=taken, n=n) if n else None


def duel(name):
    files = glob.glob(os.path.join('build', 'score', name, 'duel', '*.log'))
    if not files:
        return None
    w = l = d = 0
    for f in files:
        m = re.search(r'DUEL \d+ (WON|LOST|both stand)', clean(open(f, errors='replace').read()))
        if not m:
            continue
        w += m.group(1) == 'WON'
        l += m.group(1) == 'LOST'
        d += m.group(1) == 'both stand'
    return dict(won=w, lost=l, neither=d) if w + l + d else None


def mmss(s):
    return '-' if s >= 9999 else '%d:%02d' % (int(s) // 60, int(s) % 60)


print('%-14s %-16s %-14s %-11s %-12s %-14s %s' % ('run', 'first unit', 'T1 / T2', 'maps T2', 'raid lost/won', 'duel w/l/=', 'score'))
for name in sys.argv[1:]:
    a, r, d = alone(name), raid(name), duel(name)
    score = 0.0
    cols = []
    if a:
        cols.append('%s (%d none)' % (mmss(a['first']), a['none']))
        cols.append('%.1f / %.1f' % (a['t1'], a['t2']))
        cols.append('%d of %d' % (a['with2'], a['n']))
        score += 4 * a['with2'] - 10 * max(a['first'] / 60.0 - 3, 0)
    else:
        cols += ['', '', '']
    if r:
        cols.append('%d / %d of %d' % (r['lost'], r['taken'], r['n']))
        score += 2 * (r['taken'] - r['lost'])
    else:
        cols.append('')
    if d:
        cols.append('%d / %d / %d' % (d['won'], d['lost'], d['neither']))
        score += d['won'] - d['lost']
    else:
        cols.append('')
    print('%-14s %-16s %-14s %-11s %-12s %-14s %.0f' % (name, *cols, score))
