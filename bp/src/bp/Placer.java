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
        public final boolean liquid;
        public final List<Body.Part> makers = new ArrayList<>(), takers = new ArrayList<>();

        public Group(String res, boolean liquid){
            this.res = res;
            this.liquid = liquid;
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
    public float wHard = 1000f, wPort = 30f, wCrowd = 2f, wWire = 1f, wYield = 50f;
    /** Free tiles wanted about a part beyond its lines (raised where lines could not be laid). */
    public final Map<Body.Part, Integer> margin = new HashMap<>();
    public double cost;

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

    static int gap(Body.Part a, Body.Part c){
        int dx = Math.max(0, Math.max(c.x0 - a.x1() - 1, a.x0 - c.x1() - 1)), dy = Math.max(0, Math.max(c.y0 - a.y1() - 1, a.y0 - c.y1() - 1));
        return dx + dy;
    }

    double evaluate(){
        int w = b.w, h = b.h;
        if(cover == null){
            cover = new int[w * h];
            crowd = new float[w * h];
        }
        Arrays.fill(cover, 0);
        Arrays.fill(crowd, 0f);
        double hard = 0, wire = 0, port = 0, crowding = 0, yield = 0;
        boolean[] lane = new boolean[w * h];
        for(var e : places.entrySet()){
            Places p = e.getValue();
            int k = index(e.getKey(), p);
            if(k < 0){
                hard += 1;
                continue;
            }
            yield += Math.max(0f, p.need - p.yields.get(k));
            for(int t : p.lanes.get(k)) lane[t] = true;
        }
        for(Body body : bodies) for(Body.Part p : body.parts){
            if(!body.fixed && !b.canStand(p.block, p.x0, p.y0, p.rot)) hard += 1;
            for(int y = p.y0; y <= p.y1(); y++) for(int x = p.x0; x <= p.x1(); x++){
                if(!b.in(x, y)){
                    hard += 1;
                    continue;
                }
                int i = b.idx(x, y);
                if(cover[i]++ > 0) hard += 1;
                if(p.block.solid && (lane[i] || b.clear[i])) hard += 1;
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
                if(bg >= 0){
                    wire += bg + 1;
                    spread(best, t, bg + 1);
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
                wants.add(new int[]{gi, makes && !takes && g.liquid ? 1 : 0});
                wantPart.add(p);
            }
            //(a maker of what the core should not have, against the core)
            for(int[] t : p.around()){
                if(!b.in(t[0], t[1])) continue;
                Body.Part o = b.partAt[b.idx(t[0], t[1])];
                if(o != null && Board.isCore(o)) for(String y : Board.itemsOut(p)) if(!b.goals.contains(y)) hard += 1;
            }
        }
        port += wants.size() - match(wants, wantPart, cover);
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
        String liquid = want[0] == -2 ? Board.mustDrain(p) : want[0] >= 0 && want[1] == 1 ? groups.get(want[0]).res : null;
        for(int[] t : p.around()){
            if(!b.in(t[0], t[1]) || cover[b.idx(t[0], t[1])] != 0 || !b.floor(t[0], t[1])) continue;
            if(liquid != null && !Board.liquidsOut(p, t[2]).contains(liquid)) continue;
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
            Body body = movable.get(rng.nextInt(movable.size()));
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

    Map<Body, int[]> snapshot(){
        Map<Body, int[]> out = new HashMap<>();
        for(Body body : movable) out.put(body, new int[]{body.ox, body.oy, body.rot});
        return out;
    }

    /** The cost in parts, for the report. */
    public String explain(){
        double c = evaluate();
        return String.format("placing cost %.1f", c);
    }
}
