#!/usr/bin/env python3
"""A lab table (xtest.ModLab's out.tsv) as a library table (modules.tsv, as econ.Design.load reads it).

Every row of the lab table is kept; each gets its id (the file without .msch), its name, and as its source the pattern
line its generator wrote into pictures.txt (when one is given).

Usage: tools/lab-to-library.py lab.tsv out/modules.tsv [pictures.txt]
"""
import csv
import sys


def main():
    lab, out = sys.argv[1], sys.argv[2]
    patterns = {}
    if len(sys.argv) > 3:
        for line in open(sys.argv[3]):
            if '  ' in line and not line.startswith(' '):
                i = line.index('  ')
                patterns[line[:i]] = line[i + 2:].strip()
    rows = list(csv.reader(open(lab), delimiter='\t'))
    col = {h: i for i, h in enumerate(rows[0])}
    keep = ['size', 'blocks', 'cost', 'power', 'inputs', 'outputs', 'units_per_min', 'machines', 'stable', 'power_made', 'grids']
    lines = ['\t'.join(['id', 'file', 'name'] + keep + ['source'])]
    for r in rows[1:]:
        f = r[col['file']]
        ident = f[:-5] if f.endswith('.msch') else f
        lines.append('\t'.join([ident, f, ident] + [r[col[k]] if k in col else '' for k in keep] + ['generated: ' + patterns.get(ident, '')]))
    with open(out, 'w') as o:
        o.write('\n'.join(lines) + '\n')
    print(f'{len(lines) - 1} rows written to {out}')


if __name__ == '__main__':
    main()
