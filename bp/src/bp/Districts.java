package bp;

import mindustry.*;

import java.util.*;

/**
 * The final form placed by districts, grown out from what feeds it: the places on the ground and the core stand where
 * they are; each machine is put beside the makers that have its inputs to spare (touching one where it can, so that it is
 * handed straight in), liquids weighed most, as the community's designs stand on their pumps and vents. Room is left
 * between bodies that do not feed one another, for lines. Then mended: who feeds whom worked out again from where things
 * stand (the transportation problem, as the lines will be), each body put again beside those it now trades with.
 */
public final class Districts{
    final Board b;
    final Gen g;
    final List<Body> bodies, movable = new ArrayList<>();
    /** Free tiles kept between bodies that do not trade (a line, and a tile beside it). */
    int room = Integer.getInteger("floor.room", 2);
    final float wLiquid = Float.parseFloat(System.getProperty("floor.liquid", "3")), wItem = 1f, wPayload = 4f, wCore = Float.parseFloat(System.getProperty("floor.core", "0.2"));
    /** The body on each tile (index + 1; 0 none) and the tiles no solid block may stand on (a drill's beams, an area). */
    int[] occ;
    boolean[] noSolid;
    final Map<Body, Integer> index = new IdentityHashMap<>();
    final Set<Body> placed = Collections.newSetFromMap(new IdentityHashMap<>());

    Districts(Gen g){
        this.g = g;
        this.b = g.board;
        this.bodies = g.bodies;
        for(int i = 0; i < bodies.size(); i++) index.put(bodies.get(i), i);
        for(Body x : bodies) if(!x.fixed) movable.add(x);
        mindustry.world.Block ib = Vars.content.block("duct-bridge"), lb = Vars.content.block("reinforced-bridge-conduit");
        itemRange = ib == null ? 0 : Carriers.range(ib);
        liquidRange = lb == null ? 0 : Carriers.range(lb);
    }

    /**
     * Whether a place must leave each of its trades a way (bp.place=routed): over free ground, a bridge jumping what is in
     * the way, from a maker's side it hands the thing out of to a free tile against the taker; a maker's own pure lines
     * (a branch may come off them) and lines to the core alone (items may run into them) count as ways out too.
     */
    boolean routed;
    final Map<String, boolean[]> regions = new HashMap<>();
    int itemRange, liquidRange;

    /** One trade a body is put by: one of its parts and another's, the thing, how much it weighs. */
    record Tie(Body.Part mine, Body.Part other, String res, float w){
    }

    float weight(String res, Body.Part other){
        if(Board.isPayload(res)) return wPayload;
        if(Vars.content.liquid(res) != null) return wLiquid;
        return Board.isCore(other) ? wCore : wItem;
    }

    // ------------------------------------------------------------------ the ground

    void mark(Body x, int v){
        regions.clear();
        int k = index.get(x) + 1;
        for(Body.Part p : x.parts) for(int y = p.y0; y <= p.y1(); y++) for(int xx = p.x0; xx <= p.x1(); xx++){
            if(!b.in(xx, y)) continue;
            int i = b.idx(xx, y);
            if(v > 0) occ[i] = k;
            else if(occ[i] == k) occ[i] = 0;
        }
        for(int[] r : x.clearWorld) for(int y = Math.max(0, r[1]); y <= Math.min(b.h - 1, r[3]); y++) for(int xx = Math.max(0, r[0]); xx <= Math.min(b.w - 1, r[2]); xx++){
            if(v > 0) noSolid[b.idx(xx, y)] = true;
        }
        if(v > 0) for(int i : x.clearTiles) noSolid[i] = true;
        //(ground held whole: nothing else on it)
        for(int[] r : x.holdWorld) for(int y = Math.max(0, r[1]); y <= Math.min(b.h - 1, r[3]); y++) for(int xx = Math.max(0, r[0]); xx <= Math.min(b.w - 1, r[2]); xx++){
            int i = b.idx(xx, y);
            if(v > 0 && occ[i] == 0) occ[i] = k;
        }
    }

    /** Whether the body can stand where it is now: its ground, nothing under it, room from all but those it trades with. */
    boolean fits(Body x, Set<Body> partners){
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for(Body.Part p : x.parts){
            boolean module = p.block instanceof mindustry.world.blocks.units.UnitAssemblerModule;
            if(!module && !b.canStand(p.block, p.x0, p.y0, p.rot)) return false;
            for(int y = p.y0; y <= p.y1(); y++) for(int xx = p.x0; xx <= p.x1(); xx++){
                if(!b.in(xx, y)) return false;
                int i = b.idx(xx, y);
                if(module && !b.floor(xx, y)) return false;
                if(occ[i] != 0) return false;
                if(p.block.solid && noSolid[i]) return false;
            }
            box[0] = Math.min(box[0], p.x0);
            box[1] = Math.min(box[1], p.y0);
            box[2] = Math.max(box[2], p.x1());
            box[3] = Math.max(box[3], p.y1());
        }
        for(int[] r : x.clearWorld){
            for(int y = r[1]; y <= r[3]; y++) for(int xx = r[0]; xx <= r[2]; xx++){
                if(!b.in(xx, y)) return false;
                int i = b.idx(xx, y);
                if(occ[i] != 0 && bodies.get(occ[i] - 1).parts.stream().anyMatch(q -> q.block.solid)) return false;
            }
        }
        //(room about it: no other body within so many tiles of its box, but those it trades with)
        for(int y = box[1] - room; y <= box[3] + room; y++) for(int xx = box[0] - room; xx <= box[2] + room; xx++){
            if(!b.in(xx, y)) continue;
            int o = occ[b.idx(xx, y)];
            if(o == 0) continue;
            Body ob = bodies.get(o - 1);
            if(ob != x && !partners.contains(ob)) return false;
        }
        return true;
    }

    double cost(List<Tie> ties, Body.Part core){
        double c = 0;
        for(Tie t : ties){
            boolean liquid = Vars.content.liquid(t.res) != null;
            boolean touch = t.mine.recipe != null && (t.mine.makes(t.res) > 0f ? Router.touches(t.mine, t.other, t.res, liquid) : Router.touches(t.other, t.mine, t.res, liquid));
            c += t.w * (touch ? 0 : Placer.gap(t.mine, t.other) + 1);
        }
        //(a little toward the core, all else even: the base kept together)
        if(core != null && !ties.isEmpty()) c += 1e-3 * Placer.gap(ties.get(0).mine, core);
        return c;
    }

    /** Whether a tile is free ground for a line: nothing on it (nor held), a carrier may stand there. */
    boolean free(int x, int y){
        return b.in(x, y) && occ[b.idx(x, y)] == 0 && b.floor(x, y);
    }

    /**
     * The free ground a line of a thing can cover from a part (its sides that hand the thing out, for a maker; all its
     * sides, for a taker), and from the lines it may come off or run into: worked out once a part, a thing, a role.
     */
    boolean[] region(Body.Part p, String res, boolean maker){
        String key = System.identityHashCode(p) + "|" + res + "|" + maker;
        boolean[] got = regions.get(key);
        if(got != null) return got;
        boolean liquid = Vars.content.liquid(res) != null;
        boolean[] seen = new boolean[b.w * b.h];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        List<Body.Part> from = new ArrayList<>();
        from.add(p);
        for(Body o : bodies){
            if(o.line == null || o.parts.isEmpty()) continue;
            boolean mine = maker && o.line.pure && o.line.from == p && o.line.res.equals(res) && Router.TAPS;
            boolean toCore = !maker && Board.isCore(p) && o.line.coreBound() && !liquid;
            if(mine || toCore) from.addAll(o.parts);
        }
        for(Body.Part f : from) for(int[] t : f.around()){
            if(f == p && maker && liquid && !Board.liquidsOut(p, t[2]).contains(res)) continue;
            if(!free(t[0], t[1])) continue;
            int i = b.idx(t[0], t[1]);
            if(!seen[i]){
                seen[i] = true;
                q.add(i);
            }
        }
        int range = liquid ? liquidRange : itemRange;
        while(!q.isEmpty()){
            int c = q.poll(), cx = c % b.w, cy = c / b.w;
            for(int d = 0; d < 4; d++) for(int k = 1; k <= Math.max(1, range); k++){
                int nx = cx + Geo.dx(d) * k, ny = cy + Geo.dy(d) * k;
                if(!b.in(nx, ny)) break;
                if(!free(nx, ny)) continue;
                int ni = b.idx(nx, ny);
                if(!seen[ni]){
                    seen[ni] = true;
                    q.add(ni);
                }
            }
        }
        regions.put(key, seen);
        return seen;
    }

    /** Whether a body where it is now leaves a way for each thing it takes and makes (one of the trades of each, enough). */
    boolean routable(Body x, List<Tie> ties){
        Set<String> need = new HashSet<>(), had = new HashSet<>();
        Set<Integer> own = new HashSet<>();
        for(Body.Part p : x.parts) for(int y = p.y0; y <= p.y1(); y++) for(int xx = p.x0; xx <= p.x1(); xx++) own.add(xx + y * b.w);
        for(Tie t : ties){
            boolean makes = t.mine.makes(t.res) > 0f && t.other.takes(t.res) > 0f || Board.isCore(t.other);
            String key = (makes ? "out " : "in ") + t.res;
            need.add(key);
            if(had.contains(key)) continue;
            boolean liquid = Vars.content.liquid(t.res) != null;
            if(makes ? Router.touches(t.mine, t.other, t.res, liquid) : Router.touches(t.other, t.mine, t.res, liquid)){
                had.add(key);
                continue;
            }
            boolean[] r = region(t.other, t.res, !makes);
            for(int[] a : t.mine.around()){
                if(makes && liquid && !Board.liquidsOut(t.mine, a[2]).contains(t.res)) continue;
                if(!free(a[0], a[1]) || own.contains(a[0] + a[1] * b.w)) continue;
                if(r[b.idx(a[0], a[1])]){
                    had.add(key);
                    break;
                }
            }
        }
        return had.containsAll(need);
    }

    /** The body put where its ties cost least, searched about those it trades with, nearest first; false if nowhere. */
    boolean put(Body x, List<Tie> ties, Body.Part core){
        Set<Body> partners = Collections.newSetFromMap(new IdentityHashMap<>());
        for(Tie t : ties) partners.add(t.other.body);
        List<int[]> centres = new ArrayList<>();
        List<Tie> by = new ArrayList<>(ties);
        by.sort((p, q) -> Float.compare(q.w, p.w));
        for(Tie t : by){
            int[] c = {(t.other.x0 + t.other.x1()) / 2, (t.other.y0 + t.other.y1()) / 2};
            boolean seen = false;
            for(int[] o : centres) if(o[0] == c[0] && o[1] == c[1]) seen = true;
            if(!seen) centres.add(c);
            if(centres.size() >= 3) break;
        }
        if(centres.isEmpty()) centres.add(core != null ? new int[]{(core.x0 + core.x1()) / 2, (core.y0 + core.y1()) / 2} : new int[]{b.w / 2, b.h / 2});
        int[][] ext = new int[4][];
        for(int r = 0; r < 4; r++) ext[r] = x.extent(r);
        //(routed: of the places that fit, the cheapest that leaves every trade a way; else the cheapest that fits)
        int[] fallback = null;
        for(int reach : new int[]{8, 16, 32, 64, Math.max(b.w, b.h)}){
            List<double[]> fitting = new ArrayList<>();
            for(int[] c : centres) for(int r = 0; r < 4; r++){
                int cx = c[0] - (ext[r][0] + ext[r][2]) / 2, cy = c[1] - (ext[r][1] + ext[r][3]) / 2;
                for(int dy = -reach; dy <= reach; dy++) for(int dx = -reach; dx <= reach; dx++){
                    x.put(cx + dx, cy + dy, r);
                    if(!fits(x, partners)) continue;
                    fitting.add(new double[]{cost(ties, core), cx + dx, cy + dy, r});
                }
            }
            fitting.sort(Comparator.comparingDouble(v -> v[0]));
            if(!routed && !fitting.isEmpty() || routed && fitting.isEmpty() && fallback != null && reach == Math.max(b.w, b.h)){
                int[] at = routed ? fallback : new int[]{(int)fitting.get(0)[1], (int)fitting.get(0)[2], (int)fitting.get(0)[3]};
                x.put(at[0], at[1], at[2]);
                return true;
            }
            for(double[] v : fitting){
                x.put((int)v[1], (int)v[2], (int)v[3]);
                if(fallback == null) fallback = new int[]{(int)v[1], (int)v[2], (int)v[3]};
                if(routable(x, ties)) return true;
            }
        }
        if(fallback != null){
            x.put(fallback[0], fallback[1], fallback[2]);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ grown out

    /** What each part has to spare and still lacks, by thing, as ties are made. */
    final Map<Body.Part, Map<String, Float>> spare = new IdentityHashMap<>(), lack = new IdentityHashMap<>();

    void run(){
        long t0 = System.nanoTime();
        occ = new int[b.w * b.h];
        noSolid = new boolean[b.w * b.h];
        Body.Part core = null;
        for(Body x : bodies) if(x.fixed){
            mark(x, 1);
            placed.add(x);
            for(Body.Part p : x.parts) if(Board.isCore(p)) core = p;
        }
        for(Placer.Group gr : g.groups){
            //(of what stands already, what it has to spare and still lacks)
            for(Body.Part m : gr.makers) spare.computeIfAbsent(m, k -> new HashMap<>()).merge(gr.res, g.spare.containsKey(m) ? g.spare.get(m).getOrDefault(gr.res, 0f) : g.supply(m, gr.res), Float::sum);
            for(Body.Part t : gr.takers) lack.computeIfAbsent(t, k -> new HashMap<>()).merge(gr.res, g.lacks.containsKey(t) ? g.lacks.get(t).getOrDefault(gr.res, 0f) : g.demand(t, gr.res), Float::sum);
        }
        //(each body in turn: the one whose inputs are most to be had from what stands; what makes from nothing first)
        List<Body> left = new ArrayList<>(movable);
        int failed = 0;
        while(!left.isEmpty()){
            Body pick = null;
            double bestReady = -1;
            for(Body x : left){
                double want = 0, have = 0;
                for(Placer.Group gr : g.groups) for(Body.Part p : x.parts){
                    if(!gr.takers.contains(p) || p.body != x) continue;
                    float w = weight(gr.res, p);
                    want += w;
                    float got = 0;
                    for(Body.Part m : gr.makers) if(placed.contains(m.body) && m.body != x) got += spare.getOrDefault(m, Map.of()).getOrDefault(gr.res, 0f);
                    have += w * Math.min(1f, got / Math.max(1e-6f, g.demand(p, gr.res)));
                }
                double ready = (have + 1) / (want + 1);
                if(ready > bestReady + 1e-9){
                    bestReady = ready;
                    pick = x;
                }
            }
            left.remove(pick);
            List<Tie> ties = ties(pick, true);
            if(!put(pick, ties, core)){
                failed++;
                continue;
            }
            mark(pick, 1);
            placed.add(pick);
            take(ties);
        }
        System.out.println(String.format(Locale.ROOT, "floor: %d bodies grown out in %.1f s (%d with no room); ties cost %.1f", movable.size(), (System.nanoTime() - t0) / 1e9, failed, total(core)));
        //mended: who feeds whom from where things stand, each body put again by those
        for(int round = 0; round < Integer.getInteger("floor.mend", 2); round++){
            long t1 = System.nanoTime();
            Map<Body, List<Tie>> by = assigned();
            int moved = 0;
            for(Body x : movable){
                if(!placed.contains(x)) continue;
                int ox = x.ox, oy = x.oy, r = x.rot;
                List<Tie> ties = by.getOrDefault(x, List.of());
                double before = cost(ties, core);
                mark(x, 0);
                if(!put(x, ties, core) || cost(ties, core) > before) x.put(ox, oy, r);
                if(x.ox != ox || x.oy != oy || x.rot != r) moved++;
                mark(x, 1);
            }
            System.out.println(String.format(Locale.ROOT, "floor: mended %d, %d moved in %.1f s; ties cost %.1f", round, moved, (System.nanoTime() - t1) / 1e9, total(core)));
        }
    }

    /** The ties as the lines will be: gap by thing, and the longest. */
    void report(){
        Map<String, Integer> gapBy = new TreeMap<>(), count = new TreeMap<>();
        List<Object[]> all = new ArrayList<>();
        int touching = 0;
        for(Router.Net n : g.nets()) for(Body.Part t : n.to){
            boolean liquid = Vars.content.liquid(n.res) != null;
            boolean touch = Router.touches(n.from, t, n.res, liquid);
            int gap = touch ? 0 : Placer.gap(n.from, t) + 1;
            if(touch) touching++;
            gapBy.merge(n.res, gap, Integer::sum);
            count.merge(n.res, 1, Integer::sum);
            all.add(new Object[]{gap, n.res + " " + Gen.where(n.from) + " -> " + Gen.where(t)});
        }
        all.sort((a, c) -> Integer.compare((Integer)c[0], (Integer)a[0]));
        int sum = 0;
        for(int v : gapBy.values()) sum += v;
        System.out.println("floor: " + all.size() + " ties, " + touching + " touching, gap " + sum + " tiles; by thing (gap/ties): " + gapBy.entrySet().stream().map(e -> e.getKey() + " " + e.getValue() + "/" + count.get(e.getKey())).toList());
        for(Object[] o : all.subList(0, Math.min(30, all.size()))) System.out.println("  " + o[0] + " " + o[1]);
    }

    /** A body's ties to what stands: of each thing it takes, the nearest makers with some to spare until its need is met; of what it makes, takers still short. */
    List<Tie> ties(Body x, boolean outs){
        List<Tie> out = new ArrayList<>();
        int[] c = centre(x);
        for(Placer.Group gr : g.groups){
            for(Body.Part p : x.parts){
                if(p.body != x) continue;
                if(gr.takers.contains(p)){
                    float need = lack.getOrDefault(p, Map.of()).getOrDefault(gr.res, 0f);
                    List<Body.Part> ms = new ArrayList<>();
                    for(Body.Part m : gr.makers) if(m.body != x && placed.contains(m.body) && spare.getOrDefault(m, Map.of()).getOrDefault(gr.res, 0f) > 1e-5f) ms.add(m);
                    //(the one with most to spare first: where the thing is had, the district; then the nearest of the rest)
                    ms.sort(Comparator.comparingDouble(m -> -spare.get(m).get(gr.res)));
                    if(!ms.isEmpty()){
                        Body.Part first = ms.remove(0);
                        int[] fc = centre(first.body);
                        ms.sort(Comparator.comparingInt(m -> Math.abs(centre(m.body)[0] - fc[0]) + Math.abs(centre(m.body)[1] - fc[1])));
                        ms.add(0, first);
                    }
                    for(Body.Part m : ms){
                        if(need <= 1e-5f) break;
                        out.add(new Tie(p, m, gr.res, weight(gr.res, m)));
                        need -= spare.get(m).get(gr.res);
                    }
                }
                if(outs && gr.makers.contains(p)){
                    for(Body.Part t : gr.takers){
                        if(t.body == x || !placed.contains(t.body)) continue;
                        if(lack.getOrDefault(t, Map.of()).getOrDefault(gr.res, 0f) <= 1e-5f) continue;
                        //(the core takes what is left of every item: tied to it lightly)
                        out.add(new Tie(p, t, gr.res, weight(gr.res, t)));
                    }
                }
            }
        }
        return out;
    }

    /** What the ties carry, taken off what is spare and short (the maker's spare to the taker's lack, as far as they go). */
    void take(List<Tie> ties){
        for(Tie t : ties){
            boolean mineMakes = spare.containsKey(t.mine) && t.mine.makes(t.res) > 0f && lack.getOrDefault(t.other, Map.of()).containsKey(t.res);
            Body.Part m = mineMakes ? t.mine : t.other, k = mineMakes ? t.other : t.mine;
            float s = spare.getOrDefault(m, Map.of()).getOrDefault(t.res, 0f), l = lack.getOrDefault(k, Map.of()).getOrDefault(t.res, 0f);
            float f = Math.min(s, l);
            if(f <= 0f) continue;
            spare.get(m).put(t.res, s - f);
            lack.get(k).put(t.res, l - f);
        }
    }

    int[] centre(Body x){
        Body.Part p = x.main();
        return new int[]{(p.x0 + p.x1()) / 2, (p.y0 + p.y1()) / 2};
    }

    /** Who feeds whom as the lines will be laid (Gen.nets: least rate times gap), as ties of each body. */
    Map<Body, List<Tie>> assigned(){
        Map<Body, List<Tie>> by = new IdentityHashMap<>();
        for(Router.Net n : g.nets()) for(Body.Part t : n.to){
            float w = weight(n.res, t);
            by.computeIfAbsent(n.from.body, k -> new ArrayList<>()).add(new Tie(n.from, t, n.res, w));
            by.computeIfAbsent(t.body, k -> new ArrayList<>()).add(new Tie(t, n.from, n.res, w));
        }
        return by;
    }

    /** The ties as the placer weighs them: {maker, taker, thing, weight}. */
    List<Object[]> placerTies(){
        List<Object[]> out = new ArrayList<>();
        for(Router.Net n : g.nets()) for(Body.Part t : n.to) out.add(new Object[]{n.from, t, n.res, weight(n.res, t)});
        return out;
    }

    double total(Body.Part core){
        double c = 0;
        for(var e : assigned().entrySet()) if(!e.getKey().fixed) c += cost(e.getValue(), null);
        return c;
    }
}
