"""Picks our own generated designs (xtest.DesignGen) that the blueprint lab passed, and writes the shipped module library.

Usage: python3 -I tools/own-modules.py <gen dir> <lab turn 0.tsv> [<lab turn 1..3.tsv>...] [--write <dir>] [--community <lab.tsv>]

A design is kept when, in every table given (the lab, deployed, at each turn), it settled, spilled nothing, every working
machine ran at 90% or more on average, each output got one thing only, its power is one grid, it makes what it was drawn
to make (or power), and it makes as much turned as not. --write copies the kept ones to <dir> with a modules.tsv: the
lab's row at turn 0 with an id in front and a source column at the end. --community sets the best community designs
(from their lab table, the same rules but the grid) against ours, by rate per tile and per item of cost.
"""
import csv, os, re, shutil, sys

LINES = {'beam-node', 'beam-tower', 'duct', 'duct-bridge', 'duct-router', 'surge-conveyor', 'surge-router', 'reinforced-conduit',
         'reinforced-bridge-conduit', 'reinforced-liquid-router', 'reinforced-liquid-container', 'reinforced-liquid-tank', 'duct-unloader',
         'overflow-duct', 'underflow-duct', 'reinforced-liquid-junction', 'reinforced-junction', 'armored-duct', 'reinforced-pump', 'power-source',
         'heat-redirector', 'heat-router', 'small-heat-redirector'}


def ports(text):
    out = []
    for o in text.split(';'):
        m = re.match(r'(.+)@(-?\d+),(-?\d+)(?::\d)?=([\d.]+)', o)
        if m: out.append((m.group(1), int(m.group(2)), int(m.group(3)), float(m.group(4))))
    return out


def machines(r):
    out = []
    for m in r['machines'].split(';'):
        mm = re.match(r'(.+) x(\d+) (\d+)%', m)
        if mm: out.append((mm.group(1), int(mm.group(2)), int(mm.group(3))))
    return out


def cost(r):
    t = r['cost'].split()
    return sum(int(t[i + 1]) for i in range(0, len(t), 2))


def tiles(r):
    w, h = map(int, r['size'].split('x'))
    return w * h


def table(path):
    with open(path) as f:
        return {row['file']: row for row in csv.DictReader(f, delimiter='\t')}


def judge(r, product, community=False):
    """What is wrong with one lab row, or None; and the rate of the product (power made for power)."""
    if r is None: return 'not measured', 0
    if r['stable'] != 'settled': return r['stable'], 0
    work = [m for m in machines(r) if m[0] not in LINES]
    if not work: return 'no machines', 0
    low = min(m[2] for m in work)
    if low < 90: return f'a machine at {low}%', 0
    outs = ports(r['outputs'])
    for o in outs:
        if '+' in o[0]: return f'output mixed: {o[0]}', 0
        if o[0] == 'nothing' or o[3] <= 0.01: return 'an output gets nothing', 0
    if not community and int(r.get('grids') or 0) > 1: return r['grids'] + ' power grids', 0
    if product == 'power':
        made = float(r.get('power_made') or 0)
        return (None, made) if made > 0 else ('makes no power', 0)
    rate = sum(o[3] for o in outs if o[0] == product)
    return (None, rate) if rate > 0 else (f'makes no {product}', 0)


def main():
    args = sys.argv[1:]
    write = community = None
    if '--write' in args:
        i = args.index('--write'); write = args[i + 1]; del args[i:i + 2]
    if '--community' in args:
        i = args.index('--community'); community = args[i + 1]; del args[i:i + 2]
    gen, labs = args[0], [table(p) for p in args[1:]]
    with open(os.path.join(gen, 'designs.tsv')) as f:
        designs = list(csv.DictReader(f, delimiter='\t'))
    kept = []
    print(f"{'id':28} {'size':>6} {'cost':>5} {'power':>6} {'made':>6} {'rate':>8} {'low':>4}  verdict")
    for d in designs:
        file = d['id'] + '.msch'
        rows = [t.get(file) for t in labs]
        verdict, rate = judge(rows[0], d['product'])
        if verdict is None:
            for turn, r in enumerate(rows[1:], 1):
                v, rt = judge(r, d['product'])
                if v is not None:
                    verdict = f'turn {turn}: {v}'; break
                if abs(rt - rate) > 0.05 * rate + 0.01:
                    verdict = f'turn {turn}: makes {rt:.2f} not {rate:.2f}'; break
        r = rows[0]
        if r is None:
            print(f"{d['id']:28} not measured"); continue
        work = [m for m in machines(r) if m[0] not in LINES]
        low = min((m[2] for m in work), default=0)
        print(f"{d['id']:28} {r['size']:>6} {cost(r):5d} {float(r['power']):6.0f} {float(r.get('power_made') or 0):6.0f} {rate:8.2f} {low:3d}%  {verdict or 'kept'}")
        if verdict is None: kept.append((d, r, rate))
    print(f'{len(kept)} of {len(designs)} kept')
    if community:
        compare(kept, table(community))
    if write:
        os.makedirs(write, exist_ok=True)
        for f in os.listdir(write):
            if f.endswith('.msch'): os.remove(os.path.join(write, f))
        head = list(labs[0][next(iter(labs[0]))].keys())
        lines = ['\t'.join(['id'] + head + ['source'])]
        for d, r, _ in kept:
            shutil.copy(os.path.join(gen, d['id'] + '.msch'), os.path.join(write, d['id'] + '.msch'))
            lines.append('\t'.join([d['id']] + [r[k] for k in head] + ['generated: ' + d['pattern']]))
        with open(os.path.join(write, 'modules.tsv'), 'w') as f:
            f.write('\n'.join(lines) + '\n')
        print(f'wrote {len(kept)} modules to {write}')


def compare(kept, theirs):
    """Per product: our best by rate per tile and by rate per cost, against the community's."""
    best = {}
    for file, r in theirs.items():
        outs = ports(r['outputs'])
        if not outs: continue
        main = max(outs, key=lambda o: o[3])[0]
        v, rate = judge(r, main, community=True)
        if v is None: best.setdefault(main, []).append((r, rate))
    ours = {}
    for d, r, rate in kept: ours.setdefault(d['product'], []).append((d['id'], r, rate))
    print()
    print(f"{'product':14} {'best per tile (ours)':34} {'(community)':40} {'best per cost (ours)':34} {'(community)'}")
    for product in sorted(ours):
        mine = ours[product]
        a = max(mine, key=lambda m: m[2] / tiles(m[1]))
        b = max(mine, key=lambda m: m[2] / cost(m[1]))
        them = best.get(product, [])
        def show(r, rate, name):
            return f"{name[:18]:18} {rate:6.2f}/s {tiles(r):4d}t {rate / tiles(r) * 100:5.2f}"
        def showc(r, rate, name):
            return f"{name[:18]:18} {rate:6.2f}/s {cost(r):5d}c {rate / cost(r) * 1000:5.2f}"
        ta = max(them, key=lambda m: m[1] / tiles(m[0]), default=None)
        tb = max(them, key=lambda m: m[1] / cost(m[0]), default=None)
        print(f"{product:14} {show(a[1], a[2], a[0]):34} {show(ta[0], ta[1], ta[0]['name']) if ta else '-':40} {showc(b[1], b[2], b[0]):34} {showc(tb[0], tb[1], tb[0]['name']) if tb else '-'}")
    print('(per tile: rate per 100 tiles; per cost: rate per 1000 items of cost)')


if __name__ == '__main__':
    main()
