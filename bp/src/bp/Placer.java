package bp;

import java.util.*;

/**
 * Where the bodies go: simulated annealing over their places and turns. What a placing costs:
 * <ul>
 * <li>blocks over one another, where they cannot stand (Build.validPlace), solid ones on a beam's way: each a great deal;
 * <li>the lines it will take: for each taker of a thing, the gap to the nearest maker of it, and for each maker the gap to
 * its nearest taker (tiles of carrier between them; none when they touch and the maker hands it straight in);
 * <li>tiles about a block too few for its lines (a line needs a free tile against the block to start or end on);
 * <li>crowding (RUDY, Spindler and Johannes 2007): each line's tiles spread over the box between its ends, and what a
 * tile is asked to hold beyond one, bodies counting whole;
 * <li>a maker handing the core what is not a goal (the core takes everything);
 * <li>for a body that can only stand on its places (a drill at its ore), a place that gives less than it should.
 * </ul>
 * Moves: a body shifted (less far as it cools), turned, two swapped, one put anywhere, a drill to another of its places.
 */
public final class Placer{
    /** What is made and taken of a thing: makers and takers (parts), with their rates. */
    public static final class Group{
        public final String res;
        public final boolean liquid, payload;
        public final List<Body.Part> makers = new ArrayList<>(), takers = new ArrayList<>();
        /** For a payload: what a taker that takes a batch wants its lines to hold (payload conveyors), as ground to keep. */
        public final Map<Body.Part, Integer> buffer = new HashMap<>();

        public Group(String res, boolean liquid){
            this.res = res;
            this.liquid = liquid;
            this.payload = Board.isPayload(res);
        }
    }

    /** A body that stands only at given places: each {ox, oy, rot}, with what it gives there against what it should. */
    public static final class Places{
        public final List<int[]> at = new ArrayList<>();
        public final List<Float> yields = new ArrayList<>();
        public final List<int[]> lanes = new ArrayList<>();
        public float need;
    }

    final Board b;
    final List<Body> bodies, movable = new ArrayList<>();
    final List<Group> groups;
    final Map<Body, Places> places = new HashMap<>();
    final Random rng;
    public float wHard = 1000f, wPort = Float.parseFloat(System.getProperty("bp.port", "30")), wCrowd = Float.parseFloat(System.getProperty("bp.crowd", "8")), wWire = 1f, wYield = 50f;
    /** Free tiles wanted about a part beyond its lines (raised where lines could not be laid). */
    public final Map<Body.Part, Integer> margin = new HashMap<>();
    public double cost;
    /** Bodies to be moved most (about what the lines fought over): chosen for a move most of the time. */
    public final Set<Body> focus = new HashSet<>();

    public Placer(Board b, List<Body> bodies, List<Group> groups, long seed){
        this.b = b;
        this.bodies = bodies;
        this.groups = groups;
        this.rng = new Random(seed);
        for(Body x : bodies) if(!x.fixed) movable.add(x);
    }

    public void restrict(Body body, Places p){
        places.put(body, p);
    }

    // ------------------------------------------------------------------ the cost

    int[] cover;
    float[] crowd;
    /** Which part covers each tile as the bodies stand now (the last of them, where two do). */
    Body.Part[] partAt;

    static int gap(Body.Part a, Body.Part c){
        int dx = Math.max(0, Math.max(c.x0 - a.x1() - 1, a.x0 - c.x1() - 1)), dy = Math.max(0, Math.max(c.y0 - a.y1() - 1, a.y0 - c.y1() - 1));
        return dx + dy;
    }

    double evaluate(){
        int w = b.w, h = b.h;
        if(cover == null){
            cover = new int[w * h];
            crowd = new float[w * h];
            partAt = new Body.Part[w * h];
        }
        Arrays.fill(cover, 0);
        Arrays.fill(crowd, 0f);
        Arrays.fill(partAt, null);
        double hard = 0, wire = 0, port = 0, crowding = 0, yield = 0;
        boolean[] lane = new boolean[w * h];
        //(the areas bodies keep clear: none of two exclusive bodies over one another)
        int[] zone = new int[w * h];
        for(Body body : bodies) for(int i : body.clearTiles) lane[i] = true;
        for(Body body : bodies) for(int[] r : body.clearWorld){
            for(int y = r[1]; y <= r[3]; y++) for(int x = r[0]; x <= r[2]; x++){
                if(!b.in(x, y)){
                    if(body.exclusive) hard += hb(0, 0.2, body, null);
                    continue;
                }
                int i = b.idx(x, y);
                lane[i] = true;
                if(body.exclusive && zone[i]++ > 0) hard += hb(1, 1, body, null);
            }
        }
        for(var e : places.entrySet()){
            Places p = e.getValue();
            int k = index(e.getKey(), p);
            if(k < 0){
                hard += hb(2, 1, e.getKey(), null);
                continue;
            }
            yield += Math.max(0f, p.need - p.yields.get(k));
            for(int t : p.lanes.get(k)) lane[t] = true;
        }
        for(Body body : bodies) for(Body.Part p : body.parts){
            if(!body.fixed && !(p.block.size == 1 && p.block.buildVisibility == mindustry.world.meta.BuildVisibility.sandboxOnly ? b.floor(p.x0, p.y0) : b.canStand(p.block, p.x0, p.y0, p.rot))){
                hard += hb(3, 1, body, null);
                if(why != null) why.add("cannot stand: " + p.block.name + "@" + p.x0 + "," + p.y0 + " rot " + p.rot + " of " + body.label);
            }
            for(int y = p.y0; y <= p.y1(); y++) for(int x = p.x0; x <= p.x1(); x++){
                if(!b.in(x, y)){
                    hard += hb(4, 1, body, null);
                    continue;
                }
                int i = b.idx(x, y);
                if(cover[i]++ > 0) hard += hb(5, 1, body, partAt[i] == null ? null : partAt[i].body);
                partAt[i] = p;
                if(p.block.solid && (lane[i] || b.clear[i])) hard += hb(6, 1, body, null);
            }
        }
        //(the ground a body holds whole: no block of another body on it; where nothing stands, taken, so that no line
        //is wanted there. Two bodies may hold the same empty ground)
        for(Body body : bodies) for(int[] r : body.holdWorld) for(int y = r[1]; y <= r[3]; y++) for(int x = r[0]; x <= r[2]; x++){
            if(!b.in(x, y)) continue;
            int i = b.idx(x, y);
            Body.Part o = partAt[i];
            if(o != null && o.body != body){
                hard += hb(7, 1, body, o.body);
                if(why != null) why.add("on held ground of " + body.label + ": " + o.block.name + "@" + o.x0 + "," + o.y0 + " of " + o.body.label);
            }
            else if(o == null) cover[i] = 1;
        }
        //(a bridge of one body reaching one of another's, the first of its kind ahead within its range, as the game
        //links them (DirectionBridge.findLink): what the one carries handed into the other's line)
        for(Body body : bodies) for(Body.Part p : body.parts){
            if(!(p.block instanceof mindustry.world.blocks.distribution.DirectionBridge)) continue;
            int range = Carriers.range(p.block);
            for(int i = 1; i <= range; i++){
                int x = p.x0 + Geo.dx(p.rot) * i, y = p.y0 + Geo.dy(p.rot) * i;
                if(!b.in(x, y)) break;
                Body.Part o = partAt[b.idx(x, y)];
                if(o != null && o.block == p.block){
                    if(o.body != body) hard += hb(8, 1, body, o.body);
                    break;
                }
            }
        }
        //the lines: each taker to its nearest maker and each maker to its nearest taker
        for(Group g : groups){
            for(Body.Part t : g.takers){
                Body.Part best = null;
                int bg = Integer.MAX_VALUE;
                for(Body.Part m : g.makers){
                    if(m == t) continue;
                    int d = Router.touches(m, t, g.res, g.liquid) ? -1 : gap(m, t);
                    if(d < bg){
                        bg = d;
                        best = m;
                    }
                }
                if(best == null) continue;
                //(a payload line's buffer: so many conveyors more, laid about the taker, a conveyor's square each)
                int held = g.payload ? g.buffer.getOrDefault(t, 0) : 0;
                if(held > 0){
                    int s = 3;
                    wire += held * s;
                    spread(best, t, held * s * s);
                }
                if(bg >= 0){
                    wire += g.payload ? (bg + 1) * 3 : bg + 1;
                    spread(best, t, g.payload ? (bg + 1) * 3 : bg + 1);
                }
            }
            for(Body.Part m : g.makers){
                if(m.body.fixed) continue;
                int bg = Integer.MAX_VALUE;
                for(Body.Part t : g.takers) if(t != m) bg = Math.min(bg, Router.touches(m, t, g.res, g.liquid) ? -1 : gap(m, t));
                if(bg != Integer.MAX_VALUE && bg >= 0) wire += 0.5 * (bg + 1);
            }
        }
        //tiles about each part for its lines: each line, power, a drain takes a tile of its own against the part (a
        //tile holds one block), a liquid put out of one side only on that side (GenericCrafter.liquidOutputDirections);
        //what cannot be given its tile, the most of them matched (augmenting paths), is short
        List<int[]> wants = new ArrayList<>();
        List<Body.Part> wantPart = new ArrayList<>();
        for(Body body : bodies) for(Body.Part p : body.parts){
            int extra = margin.getOrDefault(p, 0) + (Power.needs(p.block) || Power.makes(p.block) ? 1 : 0);
            for(int k = 0; k < extra; k++){
                wants.add(new int[]{-1});
                wantPart.add(p);
            }
            String drain = Board.mustDrain(p);
            if(drain != null){
                wants.add(new int[]{-2, groups.size()});
                wantPart.add(p);
            }
            for(int gi = 0; gi < groups.size(); gi++){
                Group g = groups.get(gi);
                boolean makes = g.makers.contains(p), takes = g.takers.contains(p);
                if(!makes && !takes) continue;
                boolean touching = false;
                if(takes) for(Body.Part m : g.makers) if(m != p && Router.touches(m, p, g.res, g.liquid)) touching = true;
                if(makes && !takes){
                    touching = true;
                    for(Body.Part t : g.takers) if(t != p && !Router.touches(p, t, g.res, g.liquid)) touching = false;
                }
                if(touching) continue;
                wants.add(new int[]{gi, makes && !takes && g.payload ? 2 : makes && !takes ? 1 : 0});
                wantPart.add(p);
            }
            //(a maker of what the core should not have, against the core; a maker against another body's port (a carrier
            //on a module's edge) that takes from that side what it hands that is not the port's: mixed into its line)
            for(int[] t : p.around()){
                if(!b.in(t[0], t[1])) continue;
                Body.Part o = partAt[b.idx(t[0], t[1])];
                if(o == null) continue;
                List<String> given = Board.out(p, t[2]);
                for(String y : given) if(b.swallows(o, y)) hard += hb(9, 1, p.body, o.body);
                if(o.body != p.body && Board.isPortCarrier(o) && Board.pieceTakes(Board.asPiece(o), (t[2] + 2) % 4, null, p)) for(String y : given) if(!y.equals(o.config)) hard += hb(10, 1, p.body, o.body);
                //(a piece of another body's own line against it: what it hands taken into that line, or what that line
                //hands taken by it; a body's lines are its own)
                if(o.body != p.body && o.piece != null){
                    Board.Piece f = Body.fixed(o);
                    int back = (t[2] + 2) % 4;
                    if(!given.isEmpty() && Board.pieceTakes(f, back, p.piece != null ? Body.fixed(p) : null, p.piece != null ? null : p)) hard += hb(11, 1, p.body, o.body);
                    if(Router.has(Board.handsDirs(f), back) && takes(p, f, t[2])) hard += hb(11, 1, p.body, o.body);
                }
            }
        }
        port += wants.size() - match(wants, wantPart, cover);
        //(a payload line can join its maker and a taker only if the conveyors' squares fall in line with both: each hands
        //on only to the one exactly a square ahead, so their centres keep their place modulo a square from the first,
        //which stands just ahead of the maker's front, to the last, which lies against the taker; a maker with no taker it
        //touches or can be in line with counts short)
        for(Group g : groups){
            if(!g.payload) continue;
            for(Body.Part m : g.makers){
                boolean any = false;
                for(Body.Part t : g.takers) if(t != m && (Router.touches(m, t, g.res, false) || inLine(m, t, 3))) any = true;
                if(!any) port += 1;
            }
        }
        //(a payload leaves its maker into the square ahead of its front: a conveyor there, or its taker; each tile of
        //that square held by another body counts short)
        for(Group g : groups){
            if(!g.payload) continue;
            for(Body.Part m : g.makers){
                if(m.body.fixed) continue;
                boolean touching = false;
                for(Body.Part t : g.takers) if(t != m && Router.touches(m, t, g.res, false)) touching = true;
                if(touching) continue;
                int[] f = Board.front(m);
                int cx = f[0] + Geo.dx(m.rot), cy = f[1] + Geo.dy(m.rot);
                for(int y = cy - 1; y <= cy + 1; y++) for(int x = cx - 1; x <= cx + 1; x++){
                    if(!b.in(x, y) || !b.floor(x, y)) port += 1;
                    else if(cover[b.idx(x, y)] != 0) port += 1;
                }
            }
        }
        //(a free tile handed different things by the blocks about it takes only a carrier that faces away from all but
        //one of them: little use to any line; each thing more handed into it counts as a line end short)
        Map<Integer, Set<String>> handed = new HashMap<>();
        for(Body body : bodies) for(Body.Part p : body.parts){
            for(int[] t : p.around()){
                if(!b.in(t[0], t[1])) continue;
                int i = b.idx(t[0], t[1]);
                if(cover[i] != 0) continue;
                List<String> given = Board.out(p, t[2]);
                if(given.isEmpty()) continue;
                handed.computeIfAbsent(i, k -> new HashSet<>()).addAll(given);
            }
        }
        for(Set<String> got : handed.values()) if(got.size() > 1) port += got.size() - 1;
        for(int i = 0; i < w * h; i++){
            float over = crowd[i] + cover[i] - 1f;
            if(over > 0f) crowding += over;
        }
        return wHard * hard + wWire * wire + wPort * port + wCrowd * crowding + wYield * yield;
    }

    int[] owner;
    int seen;
    int[] seenAt;

    /** The tiles a want may take: free tiles against its part (on the side its liquid comes out of, for a one-sided maker). */
    List<Integer> tilesFor(int[] want, Body.Part p, int[] cover){
        List<Integer> out = new ArrayList<>();
        if(want[0] >= 0 && want[1] == 2){
            //(a payload leaves by the tile ahead of its maker only)
            int[] f = Board.front(p);
            if(b.in(f[0], f[1]) && cover[b.idx(f[0], f[1])] == 0 && b.floor(f[0], f[1])) out.add(b.idx(f[0], f[1]));
            return out;
        }
        //(a module's port, a carrier on its edge: an output hands on only ahead, an input is fed from behind, out of the module)
        if(want[0] >= 0 && p.recipe == null && p.config != null && (p.block instanceof mindustry.world.blocks.distribution.Duct || p.block instanceof mindustry.world.blocks.liquid.Conduit)){
            int d = want[1] == 1 ? p.rot : (p.rot + 2) % 4;
            int x = p.x0 + Geo.dx(d), y = p.y0 + Geo.dy(d);
            if(b.in(x, y) && cover[b.idx(x, y)] == 0 && b.floor(x, y)) out.add(b.idx(x, y));
            return out;
        }
        //(a module's port as the module is laid out: an input hands on only ahead; an output takes from about it but
        //ahead, off the module)
        if(want[0] >= 0 && p.port){
            for(int[] t : p.around()){
                if(!b.in(t[0], t[1]) || cover[b.idx(t[0], t[1])] != 0 || !b.floor(t[0], t[1])) continue;
                if(want[1] == 1 ? t[2] == p.rot : t[2] != p.rot) out.add(b.idx(t[0], t[1]));
            }
            return out;
        }
        //(what a maker hands out, only on the sides it hands it out of)
        String liquid = want[0] == -2 ? Board.mustDrain(p) : want[0] >= 0 && want[1] == 1 ? groups.get(want[0]).res : null;
        for(int[] t : p.around()){
            if(!b.in(t[0], t[1]) || cover[b.idx(t[0], t[1])] != 0 || !b.floor(t[0], t[1])) continue;
            if(liquid != null && !Board.out(p, t[2]).contains(liquid)) continue;
            out.add(b.idx(t[0], t[1]));
        }
        return out;
    }

    /** How many wants get a tile each, at most (Kuhn's augmenting paths). */
    int match(List<int[]> wants, List<Body.Part> parts, int[] cover){
        int n = b.w * b.h;
        if(owner == null || owner.length != n){
            owner = new int[n];
            seenAt = new int[n];
        }
        Arrays.fill(owner, -1);
        List<List<Integer>> adj = new ArrayList<>();
        for(int i = 0; i < wants.size(); i++) adj.add(tilesFor(wants.get(i), parts.get(i), cover));
        int got = 0;
        for(int i = 0; i < wants.size(); i++){
            seen++;
            if(augment(i, adj)) got++;
        }
        return got;
    }

    boolean augment(int i, List<List<Integer>> adj){
        for(int t : adj.get(i)){
            if(seenAt[t] == seen) continue;
            seenAt[t] = seen;
            if(owner[t] == -1 || augment(owner[t], adj)){
                owner[t] = i;
                return true;
            }
        }
        return false;
    }

    /**
     * Whether payload conveyors of size s can run from a maker to a taker: the first stands with its centre a step ahead of
     * the maker's front tile (across, any of three), each next one exactly s ahead of the one before
     * (PayloadConveyor.onProximityUpdate), the last against a side of the taker the taker takes from, wholly within it
     * (or at its centre, for a taker of the conveyor's size); so the first's and the last's centres must agree modulo s.
     */
    static boolean inLine(Body.Part m, Body.Part t, int s){
        int[] f = Board.front(m);
        int d = m.rot, p = (d + 1) % 4;
        for(int k = -1; k <= 1; k++){
            int sx = f[0] + Geo.dx(d) * (s / 2) + Geo.dx(p) * k, sy = f[1] + Geo.dy(d) * (s / 2) + Geo.dy(p) * k;
            for(int e = 0; e < 4; e++){
                //(the last conveyor hands along e into the taker: its front tile is the taker's first tile that way)
                int slack = (t.size - s) / 2;
                if(t.size < s) continue;
                for(int off = -slack; off <= slack; off++){
                    int ex, ey;
                    if(e == 0){
                        ex = t.x0 - (s / 2 + 1);
                        ey = t.ay() + off;
                    }else if(e == 2){
                        ex = t.x1() + (s / 2 + 1);
                        ey = t.ay() + off;
                    }else if(e == 1){
                        ey = t.y0 - (s / 2 + 1);
                        ex = t.ax() + off;
                    }else{
                        ey = t.y1() + (s / 2 + 1);
                        ex = t.ax() + off;
                    }
                    if(!Board.takesPayloadFrom(t, ex + Geo.dx(e) * (s / 2), ey + Geo.dy(e) * (s / 2))) continue;
                    if(Math.floorMod(ex - sx, s) == 0 && Math.floorMod(ey - sy, s) == 0) return true;
                }
            }
        }
        return false;
    }

    /** A line's tiles spread over the box between two parts. */
    void spread(Body.Part a, Body.Part c, int len){
        int x0 = Math.min(a.x0, c.x0), x1 = Math.max(a.x1(), c.x1()), y0 = Math.min(a.y0, c.y0), y1 = Math.max(a.y1(), c.y1());
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(b.w - 1, x1);
        y1 = Math.min(b.h - 1, y1);
        float area = (float)(x1 - x0 + 1) * (y1 - y0 + 1);
        if(area <= 0) return;
        float each = len / area;
        for(int y = y0; y <= y1; y++) for(int x = x0; x <= x1; x++) crowd[b.idx(x, y)] += each;
    }

    int index(Body body, Places p){
        for(int k = 0; k < p.at.size(); k++){
            int[] a = p.at.get(k);
            if(a[0] == body.ox && a[1] == body.oy && a[2] == body.rot) return k;
        }
        return -1;
    }

    // ------------------------------------------------------------------ the search

    void putAnywhere(Body body){
        Places p = places.get(body);
        if(p != null){
            int[] a = p.at.get(rng.nextInt(p.at.size()));
            body.put(a[0], a[1], a[2]);
            return;
        }
        int rot = rng.nextInt(4);
        int[] e = body.extent(rot);
        int x = -e[0] + rng.nextInt(Math.max(1, b.w - (e[2] - e[0]))), y = -e[1] + rng.nextInt(Math.max(1, b.h - (e[3] - e[1])));
        body.put(x, y, rot);
    }

    void refresh(){
        b.putParts(bodies);
    }

    /** Anneals from where the bodies are (or anywhere, if start), so many steps; heat scales the starting temperature. */
    public void run(int steps, boolean start){
        run(steps, start, 1.0);
    }

    public void run(int steps, boolean start, double heat){
        if(movable.isEmpty()){
            refresh();
            cost = evaluate();
            return;
        }
        if(start) for(Body body : movable) putAnywhere(body);
        //(one body to place, on ground small enough to try it everywhere: tried so, the best the start)
        if(start && movable.size() == 1 && !places.containsKey(movable.get(0)) && (long)b.w * b.h * 4 <= 40000){
            Body body = movable.get(0);
            double bestC = Double.MAX_VALUE;
            int[] bestP = null;
            for(int r = 0; r < 4; r++){
                int[] e = body.extent(r);
                for(int y = -e[1] - 1; y + e[3] <= b.h; y++) for(int x = -e[0] - 1; x + e[2] <= b.w; x++){
                    body.put(x, y, r);
                    refresh();
                    double c = evaluate();
                    if(c < bestC){
                        bestC = c;
                        bestP = new int[]{x, y, r};
                    }
                }
            }
            body.put(bestP[0], bestP[1], bestP[2]);
        }
        refresh();
        cost = evaluate();
        //(how far a move goes in cost, at the start: the temperature at which most moves are taken)
        double sum = 0;
        int n = 0;
        for(int i = 0; i < 60; i++){
            Body body = movable.get(rng.nextInt(movable.size()));
            int ox = body.ox, oy = body.oy, r = body.rot;
            putAnywhere(body);
            refresh();
            sum += Math.abs(evaluate() - cost);
            n++;
            body.put(ox, oy, r);
        }
        refresh();
        double t0 = Math.max(1e-3, heat * sum / n), t1 = 1e-3;
        double best = cost;
        Map<Body, int[]> bestAt = snapshot();
        int span = Math.max(b.w, b.h);
        for(int s = 0; s < steps; s++){
            double frac = (double)s / steps, t = t0 * Math.pow(t1 / t0, frac);
            Body body = pick();
            int ox = body.ox, oy = body.oy, r = body.rot;
            Body other = null;
            int px = 0, py = 0, pr = 0;
            int kind = rng.nextInt(10);
            if(places.containsKey(body)) putAnywhere(body);
            else if(kind < 6){
                int reach = Math.max(1, (int)Math.round(span * (1 - frac) * 0.5));
                body.put(ox + rng.nextInt(2 * reach + 1) - reach, oy + rng.nextInt(2 * reach + 1) - reach, r);
            }else if(kind < 8){
                body.put(ox, oy, (r + 1 + rng.nextInt(3)) % 4);
            }else if(kind < 9 && movable.size() > 1){
                other = movable.get(rng.nextInt(movable.size()));
                if(other == body || places.containsKey(other)) other = null;
                else{
                    px = other.ox;
                    py = other.oy;
                    pr = other.rot;
                    other.put(ox, oy, pr);
                    body.put(px, py, r);
                }
            }else putAnywhere(body);
            refresh();
            double c = evaluate();
            if(c <= cost || rng.nextDouble() < Math.exp((cost - c) / t)){
                cost = c;
                if(c < best){
                    best = c;
                    bestAt = snapshot();
                }
            }else{
                if(other != null) other.put(px, py, pr);
                body.put(ox, oy, r);
                refresh();
            }
        }
        for(var e : bestAt.entrySet()) e.getKey().put(e.getValue()[0], e.getValue()[1], e.getValue()[2]);
        refresh();
        cost = evaluate();
    }

    List<Body> focused = new ArrayList<>();

    Body pick(){
        if(!focus.isEmpty() && rng.nextInt(10) < 7){
            if(focused.size() != focus.size()){
                focused.clear();
                for(Body x : focus) if(!x.fixed) focused.add(x);
            }
            if(!focused.isEmpty()) return focused.get(rng.nextInt(focused.size()));
        }
        return movable.get(rng.nextInt(movable.size()));
    }

    Map<Body, int[]> snapshot(){
        Map<Body, int[]> out = new HashMap<>();
        for(Body body : movable) out.put(body, new int[]{body.ox, body.oy, body.rot});
        return out;
    }

    /** Whether a part takes what a piece of a line hands it, from its side toward dir. */
    boolean takes(Body.Part p, Board.Piece from, int dir){
        if(p.piece != null) return Board.pieceTakes(Body.fixed(p), dir, from, null);
        if(Board.isPortCarrier(p)) return Board.pieceTakes(Board.asPiece(p), dir, from, null);
        return b.partTakes(p, from.res) || b.swallows(p, from.res);
    }

    /** The hard terms by kind, in the order they are counted in evaluate (for the report). */
    final double[] hardBy = new double[16];

    double hb(int k, double v, Body one, Body two){
        hardBy[k] += v;
        if(hardWho != null){
            if(one != null) hardWho.add(one);
            if(two != null) hardWho.add(two);
        }
        return v;
    }

    Set<Body> hardWho;

    /** The bodies that break what may not be broken, where they stand now: on ground they cannot stand on, over another... */
    public Set<Body> hardBodies(){
        hardWho = new HashSet<>();
        evaluate();
        Set<Body> out = hardWho;
        hardWho = null;
        return out;
    }

    List<String> why;

    /** The cost in parts, for the report. */
    public String explain(){
        java.util.Arrays.fill(hardBy, 0);
        why = new ArrayList<>();
        double c = evaluate();
        String reasons = String.join("; ", why);
        why = null;
        StringBuilder s = new StringBuilder(String.format("placing cost %.1f; hard by kind:", c));
        for(int i = 0; i < hardBy.length; i++) if(hardBy[i] > 0) s.append(" ").append(i).append("=").append(hardBy[i]);
        if(!reasons.isEmpty()) s.append(" (").append(reasons).append(")");
        return s.toString();
    }
}
