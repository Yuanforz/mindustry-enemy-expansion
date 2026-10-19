package expansion.econ;

import arc.struct.*;
import mindustry.*;
import mindustry.world.*;

import java.util.*;

/**
 * Where the machines that do not work the ground go: placed by simulated annealing, the way chips are floorplanned and
 * factories laid out (the facility layout problem), over what each would cost in lines and what it would be in the way of.
 *
 * What is placed are macros: a unit factory with the refabricator that takes its units standing in front of it (the units
 * go straight in, so these two always come together), and every other machine alone. Each has a place and a facing.
 *
 * What a layout costs:
 * <ul>
 * <li>for each thing a machine makes or takes, how far it is from the nearest other machine of that net (and from the core,
 * for what the core keeps): about how long the line will be, the way placement tools count wire;</li>
 * <li>nothing for a maker that touches a machine that takes what it gives on that side (the furnace against the factory,
 * the electrolyzer's hydrogen side against the refabricator): no line at all, and a little better than that, as it saves
 * room; but touching is not forced, since a machine pressed against others has fewer sides left for its lines;</li>
 * <li>a lot for a maker touching a machine that would take what it gives but is not meant to (it hands out to every
 * neighbour by turns, so that would be lost to it);</li>
 * <li>for each machine with fewer free tiles round its edge than its lines need;</li>
 * <li>the tiles kept for fields to be mined later that it covers, and a little for being far from the core.</li>
 * </ul>
 * Never: overlapping anything, standing on ground drills reach across, in front of where units come out, or on the ring
 * round the core that lines into it need.
 */
final class Cells{
    final Grid grid;
    final Catalog cat;
    final Field field;
    final Random rand;
    final List<Unit> units = new ArrayList<>();
    /** The nets as the placement sees them: who makes and who takes each thing. */
    final List<NetModel> nets = new ArrayList<>();
    /** Machines to place, and those that stand already (on the ground, or the map's own), all by where they are now. */
    final Map<Machine, int[]> where = new HashMap<>();
    final int coreSize;
    /** Tiles covered, and tiles kept clear in front of a factory, by the units placed (unit index + 1; 0 for none). */
    final IntIntMap foot = new IntIntMap(), exit = new IntIntMap();
    /** How much more a net's lines count, for nets that could not be laid on a try before. */
    final Map<Integer, Float> stress = new HashMap<>();
    public int iterations = 40000;
    public float radius = 55f;
    float best;

    static final class NetModel{
        final int res;
        final boolean toCore, liquid;
        final List<Machine> from = new ArrayList<>(), to = new ArrayList<>();
        final float weight;

        NetModel(int res, boolean toCore, boolean liquid, float weight){
            this.res = res;
            this.toCore = toCore;
            this.liquid = liquid;
            this.weight = weight;
        }
    }

    /** A rigid group placed as one: its members' offsets and facings, turned with it. */
    static final class Unit{
        final List<Machine> ms = new ArrayList<>();
        final List<int[]> rel = new ArrayList<>();
        int x, y, o;
        boolean electrolyzer;
    }

    Cells(Grid grid, Catalog cat, Field field, long seed){
        this.grid = grid;
        this.cat = cat;
        this.field = field;
        rand = new Random(seed);
        coreSize = grid.team.core().block.size;
    }

    static Block block(Machine m){
        return Vars.content.block(m.recipe.block);
    }

    /** Who makes and takes what, from the machines of the order and those that stand. */
    void model(List<Machine> order, List<Machine> existing){
        for(int k = 0; k < cat.size(); k++){
            Res r = cat.get(k);
            if(r.kind == Res.Kind.unit) continue;
            NetModel n = new NetModel(k, r.kind == Res.Kind.item && r.storable, r.kind == Res.Kind.liquid, r.kind == Res.Kind.liquid ? 2f : 1f);
            for(Machine m : order){
                if(m.out(k) > 1e-4f) n.from.add(m);
                if(takes(m, k)) n.to.add(m);
            }
            if(r.kind == Res.Kind.liquid) for(Machine m : existing) if(m.out(k) > 1e-4f) n.from.add(m);
            if((n.from.isEmpty() || n.to.isEmpty()) && !(n.toCore && !n.from.isEmpty())) continue;
            nets.add(n);
        }
        for(Machine m : order) if(m.recipe.ground != Recipe.Ground.none) where.put(m, new int[]{m.x, m.y, m.rotation});
        for(Machine m : existing) where.put(m, new int[]{m.x, m.y, m.rotation});
    }

    static boolean takes(Machine m, int k){
        return m.recipe.in[k] > 0f || (m.boosted && m.recipe.boostRes == k);
    }

    /** Puts the machines into macros: each refabricator in front of a factory making what it takes. */
    void group(List<Machine> cells){
        List<Machine> left = new ArrayList<>(cells);
        for(Machine r : cells){
            if(r.recipe.unitIn < 0) continue;
            Machine f = null;
            for(Machine c : left) if(c != r && c.recipe.makesUnits() && c.recipe.unitIn < 0 && c.recipe.unitOut == r.recipe.unitIn){ f = c; break; }
            if(f == null) continue;
            Unit u = new Unit();
            int sf = block(f).size, sr = block(r).size;
            u.ms.add(f);
            u.rel.add(new int[]{0, 0, 0});
            u.ms.add(r);
            u.rel.add(new int[]{sf / 2 + (sr + 1) / 2, 0, 0});
            left.remove(f);
            left.remove(r);
            units.add(u);
        }
        for(Machine m : left){
            Unit u = new Unit();
            u.ms.add(m);
            u.rel.add(new int[]{0, 0, 0});
            u.electrolyzer = m.recipe.out[hydrogen()] > 0f;
            units.add(u);
        }
        //the big ones first, for the first placing
        units.sort((a, b) -> Integer.compare(area(b), area(a)));
    }

    int hydrogen(){
        try{
            return cat.id("hydrogen");
        }catch(Exception e){
            return 0;
        }
    }

    static int area(Unit u){
        int a = 0;
        for(Machine m : u.ms) a += block(m).size * block(m).size;
        return a;
    }

    /** Where a member of a unit stands and faces, for the unit's place and facing. */
    static int[] member(Unit u, int i, int x, int y, int o){
        int[] r = u.rel.get(i);
        int dx = r[0], dy = r[1];
        for(int k = 0; k < o; k++){
            int t = dx;
            dx = -dy;
            dy = t;
        }
        return new int[]{x + dx, y + dy, (r[2] + o) % 4};
    }

    // ---------------------------------------------------------------- where a unit may stand

    boolean coreRing(int x, int y){
        int off = -(coreSize - 1) / 2;
        int x0 = field.coreX + off - 2, y0 = field.coreY + off - 2, x1 = field.coreX + off + coreSize + 1, y1 = field.coreY + off + coreSize + 1;
        return x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }

    /** Whether unit ui could stand at x, y facing o, with the others where they are. */
    boolean feasible(int ui, int x, int y, int o){
        Unit u = units.get(ui);
        if(Math.hypot(x - field.coreX, y - field.coreY) > radius) return false;
        for(int i = 0; i < u.ms.size(); i++){
            Machine m = u.ms.get(i);
            Block b = block(m);
            int[] p = member(u, i, x, y, o);
            if(!grid.fits(b, p[0], p[1])) return false;
            for(int t : grid.tiles(b, p[0], p[1])){
                int f = foot.get(t, 0), e = exit.get(t, 0);
                if((f != 0 && f != ui + 1) || (e != 0 && e != ui + 1)) return false;
                if(coreRing(t % grid.w, t / grid.w)) return false;
            }
            int[] ex = exitOf(u, i, p);
            if(ex != null){
                for(int t : ex){
                    if(t < 0 || !grid.free(t % grid.w, t / grid.w)) return false;
                    int f = foot.get(t, 0);
                    if(f != 0 && f != ui + 1) return false;
                }
            }
        }
        return true;
    }

    /** The tiles in front of a member that makes units and hands them to nothing of its own unit. */
    int[] exitOf(Unit u, int i, int[] p){
        Machine m = u.ms.get(i);
        if(!m.recipe.makesUnits()) return null;
        //a factory whose refabricator stands in front of it hands its units straight in
        if(i == 0 && u.ms.size() > 1 && u.ms.get(1).recipe.unitIn == m.recipe.unitOut) return null;
        return grid.front(block(m), p[0], p[1], p[2], 2);
    }

    void put(int ui){
        Unit u = units.get(ui);
        for(int i = 0; i < u.ms.size(); i++){
            int[] p = member(u, i, u.x, u.y, u.o);
            Machine m = u.ms.get(i);
            where.put(m, p);
            for(int t : grid.tiles(block(m), p[0], p[1])) foot.put(t, ui + 1);
            int[] ex = exitOf(u, i, p);
            if(ex != null) for(int t : ex) if(t >= 0) exit.put(t, ui + 1);
        }
    }

    void lift(int ui){
        Unit u = units.get(ui);
        for(int i = 0; i < u.ms.size(); i++){
            Machine m = u.ms.get(i);
            int[] p = where.remove(m);
            if(p == null) continue;
            for(int t : grid.tiles(block(m), p[0], p[1])) if(foot.get(t, 0) == ui + 1) foot.remove(t, 0);
            int[] ex = exitOf(u, i, p);
            if(ex != null) for(int t : ex) if(t >= 0 && exit.get(t, 0) == ui + 1) exit.remove(t, 0);
        }
    }

    // ---------------------------------------------------------------- what a layout costs

    int[] rect(Machine m){
        int[] p = where.get(m);
        int s = block(m).size, off = -(s - 1) / 2;
        return new int[]{p[0] + off, p[1] + off, p[0] + off + s - 1, p[1] + off + s - 1};
    }

    int[] coreRect(){
        int off = -(coreSize - 1) / 2;
        return new int[]{field.coreX + off, field.coreY + off, field.coreX + off + coreSize - 1, field.coreY + off + coreSize - 1};
    }

    /** Tiles of ground between two rectangles, 0 when they touch. */
    static int gap(int[] a, int[] b){
        int gx = Math.max(0, Math.max(b[0] - a[2] - 1, a[0] - b[2] - 1));
        int gy = Math.max(0, Math.max(b[1] - a[3] - 1, a[1] - b[3] - 1));
        return gx + gy;
    }

    /** The side of a that b touches along an edge, -1 if they do not share one. */
    static int side(int[] a, int[] b){
        boolean ox = a[0] <= b[2] && b[0] <= a[2], oy = a[1] <= b[3] && b[1] <= a[3];
        if(oy && b[0] == a[2] + 1) return 0;
        if(ox && b[1] == a[3] + 1) return 1;
        if(oy && b[2] == a[0] - 1) return 2;
        if(ox && b[3] == a[1] - 1) return 3;
        return -1;
    }

    /** Whether maker p, as it stands, hands thing k straight to the machine c it touches. */
    boolean feeds(Machine p, Machine c, int k){
        int s = side(rect(p), rect(c));
        if(s < 0) return false;
        if(cat.get(k).kind == Res.Kind.liquid) return grid.liquidOut(block(p), where.get(p)[2], s) == k;
        return p.out(k) > 1e-4f;
    }

    float cost(){
        float c = 0f;
        Set<Machine> cells = new HashSet<>();
        for(Unit u : units) cells.addAll(u.ms);
        int[] core = coreRect();
        Map<Machine, Integer> ports = new HashMap<>();
        for(NetModel n : nets){
            float w = n.weight * stress.getOrDefault(n.res, 1f);
            List<Machine> all = new ArrayList<>(n.from);
            all.addAll(n.to);
            for(Machine m : all){
                if(!cells.contains(m) || !where.containsKey(m)) continue;
                boolean maker = n.from.contains(m);
                int[] r = rect(m);
                int d = Integer.MAX_VALUE;
                boolean direct = false;
                for(Machine o : all){
                    if(o == m || !where.containsKey(o)) continue;
                    if(maker && n.to.contains(o) && feeds(m, o, n.res)){ direct = true; d = 0; break; }
                    if(!maker && n.from.contains(o) && feeds(o, m, n.res)){ direct = true; d = 0; break; }
                    //only maker to taker counts: two takers of a thing side by side share nothing
                    if(maker == n.from.contains(o) && !(n.toCore)) continue;
                    d = Math.min(d, gap(r, rect(o)));
                }
                if(n.toCore && !direct) d = Math.min(d, gap(r, core));
                if(d == Integer.MAX_VALUE) d = 40;
                c += direct ? -2f : w * (d + 1);
                if(!direct) ports.merge(m, 1, Integer::sum);
            }
        }
        for(Unit u : units){
            for(Machine m : u.ms){
                if(!where.containsKey(m)) continue;
                int[] r = rect(m);
                //a maker against something that would take what it gives and is not meant to
                for(int k = 0; k < cat.size(); k++){
                    if(m.out(k) <= 1e-4f || cat.get(k).kind == Res.Kind.unit) continue;
                    for(var e : where.entrySet()){
                        Machine o = e.getKey();
                        if(o == m || takes(o, k) || u.ms.contains(o)) continue;
                        if(side(r, rect(o)) >= 0 && feedsAny(m, o, k) && grid.uses(block(o), k)) c += 30f;
                    }
                }
                //room round it for its lines
                int need = ports.getOrDefault(m, 0), free = 0;
                for(int[] t : Grid.around(block(m).size, where.get(m)[0], where.get(m)[1])){
                    if(!grid.free(t[0], t[1])) continue;
                    int q = grid.pos(t[0], t[1]);
                    if(foot.get(q, 0) != 0) continue;
                    free++;
                }
                if(free < need * 2 + 1) c += 8f * (need * 2 + 1 - free);
                for(int t : grid.tiles(block(m), where.get(m)[0], where.get(m)[1])) c += grid.kept[t];
                c += 0.08f * gap(r, core);
            }
        }
        return c;
    }

    boolean feedsAny(Machine p, Machine c, int k){
        int s = side(rect(p), rect(c));
        if(s < 0) return false;
        if(cat.get(k).kind == Res.Kind.liquid) return grid.liquidOut(block(p), where.get(p)[2], s) == k;
        return true;
    }

    // ---------------------------------------------------------------- the search

    /** Places every unit, first greedily near what it works with, then anneals. False if some unit has no room at all. */
    boolean place(){
        for(int ui = 0; ui < units.size(); ui++){
            Unit u = units.get(ui);
            int[] aim = aim(u);
            float bestCost = Float.MAX_VALUE;
            int[] bestAt = null;
            int found = 0;
            //a spiral out from where it would best be, the first few places that fit tried for cost
            outer:
            for(int r = 0; r <= (int)radius * 2; r++){
                for(int dx = -r; dx <= r; dx++){
                    for(int dy = -r; dy <= r; dy++){
                        if(Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                        for(int o = 0; o < 4; o++){
                            int x = aim[0] + dx, y = aim[1] + dy;
                            if(!feasible(ui, x, y, o)) continue;
                            u.x = x;
                            u.y = y;
                            u.o = o;
                            put(ui);
                            float c = cost();
                            lift(ui);
                            if(c < bestCost){
                                bestCost = c;
                                bestAt = new int[]{x, y, o};
                            }
                            if(++found >= 60) break outer;
                        }
                    }
                }
            }
            if(bestAt == null) return false;
            u.x = bestAt[0];
            u.y = bestAt[1];
            u.o = bestAt[2];
            put(ui);
        }
        anneal();
        return true;
    }

    /** Where a unit would best be at first: amid the machines that stand already that it works with, or by the core. */
    int[] aim(Unit u){
        float sx = 0, sy = 0;
        int n = 0;
        for(NetModel net : nets){
            boolean in = false;
            for(Machine m : u.ms) if(net.from.contains(m) || net.to.contains(m)) in = true;
            if(!in) continue;
            for(Machine o : net.from) if(!u.ms.contains(o) && where.containsKey(o)){ sx += where.get(o)[0]; sy += where.get(o)[1]; n++; }
            for(Machine o : net.to) if(!u.ms.contains(o) && where.containsKey(o)){ sx += where.get(o)[0]; sy += where.get(o)[1]; n++; }
            if(net.toCore){ sx += field.coreX * 2; sy += field.coreY * 2; n += 2; }
        }
        if(n == 0) return new int[]{field.coreX + coreSize, field.coreY};
        //not further out than the radius allows
        float x = sx / n, y = sy / n;
        float d = (float)Math.hypot(x - field.coreX, y - field.coreY);
        if(d > radius * 0.6f){
            x = field.coreX + (x - field.coreX) * radius * 0.6f / d;
            y = field.coreY + (y - field.coreY) * radius * 0.6f / d;
        }
        return new int[]{Math.round(x), Math.round(y)};
    }

    void anneal(){
        float cur = cost();
        best = cur;
        int[][] bestState = state();
        float t0 = 20f, t1 = 0.05f;
        for(int it = 0; it < iterations; it++){
            float t = t0 * (float)Math.pow(t1 / t0, it / (float)iterations);
            int ui = rand.nextInt(units.size());
            Unit u = units.get(ui);
            int ox = u.x, oy = u.y, oo = u.o;
            int nx = ox, ny = oy, no = oo;
            int other = -1;
            float roll = rand.nextFloat();
            if(roll < 0.55f){
                int r = Math.max(1, Math.round(12f * t / t0 + 1));
                nx += rand.nextInt(2 * r + 1) - r;
                ny += rand.nextInt(2 * r + 1) - r;
            }else if(roll < 0.7f){
                no = (oo + 1 + rand.nextInt(3)) % 4;
            }else if(roll < 0.78f){
                nx = field.coreX + rand.nextInt((int)radius * 2 + 1) - (int)radius;
                ny = field.coreY + rand.nextInt((int)radius * 2 + 1) - (int)radius;
                no = rand.nextInt(4);
            }else if(roll < 0.88f && units.size() > 1){
                other = rand.nextInt(units.size());
                if(other == ui) other = -1;
            }else if(u.electrolyzer){
                int[] s = snap(u);
                if(s == null) continue;
                nx = s[0];
                ny = s[1];
                no = s[2];
            }else{
                continue;
            }
            if(other >= 0){
                Unit v = units.get(other);
                lift(ui);
                lift(other);
                int vx = v.x, vy = v.y;
                boolean ok = feasible(ui, vx, vy, u.o);
                if(ok){
                    u.x = vx;
                    u.y = vy;
                    put(ui);
                    ok = feasible(other, ox, oy, v.o);
                    if(!ok) lift(ui);
                }
                if(!ok){
                    u.x = ox;
                    u.y = oy;
                    put(ui);
                    put(other);
                    continue;
                }
                v.x = ox;
                v.y = oy;
                put(other);
                float c = cost();
                if(c <= cur || rand.nextFloat() < Math.exp((cur - c) / t)){
                    cur = c;
                }else{
                    lift(ui);
                    lift(other);
                    u.x = ox; u.y = oy;
                    v.x = vx; v.y = vy;
                    put(ui);
                    put(other);
                }
            }else{
                lift(ui);
                if(!feasible(ui, nx, ny, no)){
                    put(ui);
                    continue;
                }
                u.x = nx;
                u.y = ny;
                u.o = no;
                put(ui);
                float c = cost();
                if(c <= cur || rand.nextFloat() < Math.exp((cur - c) / t)){
                    cur = c;
                }else{
                    lift(ui);
                    u.x = ox;
                    u.y = oy;
                    u.o = oo;
                    put(ui);
                }
            }
            if(cur < best - 1e-4f){
                best = cur;
                bestState = state();
            }
        }
        restore(bestState);
    }

    /** An electrolyzer put against a refabricator's side, its hydrogen side towards it. */
    int[] snap(Unit u){
        Machine e = u.ms.get(0);
        List<Machine> refabs = new ArrayList<>();
        for(Unit v : units) for(Machine m : v.ms) if(m.recipe.unitIn >= 0 && m.recipe.in[hydrogen()] > 0f) refabs.add(m);
        if(refabs.isEmpty()) return null;
        Machine r = refabs.get(rand.nextInt(refabs.size()));
        int[] rp = where.get(r);
        if(rp == null) return null;
        int side = (rp[2] + 1 + 2 * rand.nextInt(2)) % 4;
        int se = block(e).size, sr = block(r).size;
        int d = sr / 2 + (se + 1) / 2;
        int x = rp[0] + arc.math.geom.Geometry.d4x(side) * d, y = rp[1] + arc.math.geom.Geometry.d4y(side) * d;
        //which facing puts the hydrogen out of the side towards the refabricator
        for(int o = 0; o < 4; o++){
            if(grid.liquidOut(block(e), o, (side + 2) % 4) == hydrogen()) return new int[]{x, y, o};
        }
        return null;
    }

    int[][] state(){
        int[][] s = new int[units.size()][];
        for(int i = 0; i < units.size(); i++) s[i] = new int[]{units.get(i).x, units.get(i).y, units.get(i).o};
        return s;
    }

    void restore(int[][] s){
        for(int i = 0; i < units.size(); i++) lift(i);
        for(int i = 0; i < units.size(); i++){
            Unit u = units.get(i);
            u.x = s[i][0];
            u.y = s[i][1];
            u.o = s[i][2];
            put(i);
        }
    }

    /** Each placed machine's place and facing (a block that does not turn faces 0). */
    Map<Machine, int[]> result(){
        Map<Machine, int[]> out = new HashMap<>();
        for(Unit u : units){
            for(Machine m : u.ms){
                int[] p = where.get(m).clone();
                if(!block(m).rotate) p[2] = 0;
                out.put(m, p);
            }
        }
        return out;
    }
}
