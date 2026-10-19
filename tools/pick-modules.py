import csv,collections,re,sys
rows=list(csv.DictReader(open(sys.argv[2] if len(sys.argv)>2 else 'build/lab/all.tsv'),delimiter='\t'))
def parse_ports(s):
    out=[]
    for o in s.split(';'):
        m=re.match(r'(.+)@(-?\d+),(-?\d+)(?::\d)?=([\d.]+)',o)
        if m: out.append((m.group(1),int(m.group(2)),int(m.group(3)),float(m.group(4))))
    return out
def machines(r):
    ms=[]
    for m in r['machines'].split(';'):
        mm=re.match(r'(.+) x(\d+) (\d+)%',m)
        if mm: ms.append((mm.group(1),int(mm.group(2)),int(mm.group(3))))
    return ms
def costsum(r):
    t=r['cost'].split(); return sum(int(t[i+1]) for i in range(0,len(t),2))
cands=collections.defaultdict(list)
for r in rows:
    if r['stable']!='settled': continue
    ins=parse_ports(r['inputs']); outs=parse_ports(r['outputs'])
    ms=machines(r)
    work=[m for m in ms if m[0] not in ('beam-node','beam-tower','duct','duct-bridge','duct-router','surge-conveyor','surge-router','reinforced-conduit','reinforced-bridge-conduit','reinforced-liquid-router','reinforced-liquid-container','reinforced-liquid-tank','duct-unloader','overflow-duct','underflow-duct','reinforced-liquid-junction','reinforced-junction','armored-duct','reinforced-pump','power-source')]
    if not work: continue
    low=min(m[2] for m in work)
    prods=[o for o in outs if o[3]>0.01 and o[0]!='nothing' and '+' not in o[0]]
    if len(prods)!=len([o for o in outs if o[3]>0.01]): continue  # mixed outputs
    w,h=map(int,r['size'].split('x'))
    if not prods and r['units_per_min']:
        for u in r['units_per_min'].split(';'):
            if '=' in u:
                n,v=u.split('='); prods.append(('unit:'+n,0,0,float(v)))
    if not prods: continue
    main=max(prods,key=lambda p:p[3])
    cands[main[0]].append(dict(file=r['file'],name=r['name'],size=r['size'],area=w*h,rate=main[3],low=low,cost=costsum(r),power=float(r['power']),ins=ins,outs=prods,work=work))
for k in sorted(cands):
    v=sorted(cands[k],key=lambda c:-c['rate']/c['area'])
    print(f"== {k}: {len(v)}")
    for c in v[:6]:
        print(f"   {c['rate']:7.2f}/s {c['size']:>6} low {c['low']:3d}% cost {c['cost']:5d} pow {c['power']:6.0f}  {c['file']} \"{c['name']}\"  in: "+', '.join(f"{i[0]} {i[3]:.2f}" for i in c['ins']))

# the library: per product up to 3, low >= 90, the best rate per tile first; ids product-n
import shutil,os,glob
if len(sys.argv)>1 and sys.argv[1]=='write':
    out='blueprints/modules'
    os.makedirs(out,exist_ok=True)
    for f in glob.glob(out+'/*.msch'): os.remove(f)
    lines=[open(sys.argv[2] if len(sys.argv)>2 else 'build/lab/all.tsv').readline().rstrip('\n').replace('file\t','id\tfile\t',1)]
    byfile={r['file']:r for r in rows}
    paths={os.path.basename(p):p for p in glob.glob('reference/schematics/**/*.msch',recursive=True)}
    seen=set()
    for k in sorted(cands):
        if k.startswith('unit:'): continue
        v=[c for c in cands[k] if c['low']>=90]
        # the same design under another name: same size and the same inputs
        uniq={}
        for c in v: uniq.setdefault((c['size'],tuple(sorted((i[0],round(i[3],1)) for i in c['ins']))),c)
        v=list(uniq.values())
        picks=[]
        for key in (lambda c:-c['rate']/c['area'], lambda c:c['cost']/max(c['rate'],1e-3)*0+c['cost'], lambda c:(len(set(i[0] for i in c['ins'])),c['cost'])):
            for c in sorted(v,key=key):
                if c not in picks:
                    picks.append(c); break
        n=0
        for c in picks:
            if c['name'] in seen: continue
            seen.add(c['name'])
            mid=f"{k}-{n+1}"
            shutil.copy(paths[c['file']], f"{out}/{mid}.msch")
            r=byfile[c['file']]
            lines.append(mid+'\t'+'\t'.join(r[x] for x in r))
            n+=1
    open(out+'/modules.tsv','w').write('\n'.join(lines)+'\n')
    print(len(lines)-1,'modules')
