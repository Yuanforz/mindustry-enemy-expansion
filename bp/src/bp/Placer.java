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
    /**
     * Where the builder is about (the core): each body that moves weighs its way from there, a little a tile, as the
     * builder flies out to put it down; else a body with no line (a wall, a sign) stood anywhere alike, the far corners
     * of the map as well as by the base.
     */
    public Body.Part home;
    public float wTravel = Float.parseFloat(System.getProperty("bp.travel", "0.1"));
    public double cost;
    /** Bodies to be moved most (about what the lines fought over): chosen for a move most of the time. */
    public final Set<Body> focus = new LinkedHashSet<>();

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
    Body[] laneOf;
    /** Which part covers each tile as the bodies stand now (the last of them, where two do). */
    Body.Part[] partAt;

    static int gap(Body.Part a, Body.Part c){
        int dx = Math.max(0, Math.max(c.x0 - a.x1() - 1, a.x0 - c.x1() - 1)), dy = Math.max(0, Math.max(c.y0 - a.y1() - 1, a.y0 - c.y1() - 1));
        return dx + dy;
    }

    /** Whether each quick count is checked against the whole one (a test of the quick one). */
    static final boolean CHECK = Boolean.getBoolean("bp.checkEval");

    /** The cost of the bodies as they stand now: counted quickly while searching, whole for the report and the blame. */
    double evaluate(){
        if(why != null || hardWho != null || !prepared) return evaluateFull();
        double f = evaluateFast();
        if(CHECK){
            double[] quick = last.clone();
            double g = evaluateFull();
            if(Math.abs(f - g) > 1e-6 * Math.max(1.0, Math.abs(g))) throw new IllegalStateException(String.format(Locale.ROOT, "quick count %.6f (hard %.3f wire %.3f port %.3f crowd %.3f yield %.3f) against whole %.6f (hard %.3f wire %.3f port %.3f crowd %.3f yield %.3f)", f, quick[0], quick[1], quick[2], quick[3], quick[4], g, last[0], last[1], last[2], last[3], last[4]));
        }
        return f;
    }

    /** The last count by its terms: hard, wire, port, crowding, yield. */
    final double[] last = new double[5];

    void ensureArrays(){
        int n = b.w * b.h;
        if(cover != null && cover.length == n) return;
        cover = new int[n];
        crowd = new float[n];
        partAt = new Body.Part[n];
        laneOf = new Body[n];
    }

    /** A tile some body keeps clear: whose it is, one that is not standing before one that is (what is solid there is its trouble). */
    static void markLane(boolean[] lane, Body[] laneOf, int i, Body body){
        lane[i] = true;
        if(laneOf[i] == null || laneOf[i].standing && !body.standing) laneOf[i] = body;
    }

    /** The whole count: every body, every term (the order: what does not move first, then what does). */
    double evaluateFull(){
        int w = b.w, h = b.h;
        ensureArrays();
        Arrays.fill(cover, 0);
        Arrays.fill(crowd, 0f);
        Arrays.fill(partAt, null);
        Arrays.fill(laneOf, null);
        double hard = 0, wire = 0, port = 0, crowding = 0, yield = 0;
        boolean[] lane = new boolean[w * h];
        List<Body> order = order();
        //(the areas bodies keep clear: none of two exclusive bodies over one another)
        int[] zone = new int[w * h];
        for(Body body : order) for(int i : body.clearTiles) markLane(lane, laneOf, i, body);
        for(Body body : order) for(int[] r : body.clearWorld){
            for(int y = r[1]; y <= r[3]; y++) for(int x = r[0]; x <= r[2]; x++){
                if(!b.in(x, y)){
                    if(body.exclusive) hard += hb(0, 0.2, body, null);
                    continue;
                }
                int i = b.idx(x, y);
                markLane(lane, laneOf, i, body);
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
            for(int t : p.lanes.get(k)) markLane(lane, laneOf, t, e.getKey());
        }
        //(what covers a tile is the first part on it, as counted)
        for(Body body : order) for(Body.Part p : body.parts){
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
                if(partAt[i] == null) partAt[i] = p;
                if(p.block.solid && (lane[i] || b.clear[i])) hard += hb(6, 1, body, laneOf[i]);
            }
        }
        //(the ground a body holds whole: no block of another body on it; where nothing stands, taken, so that no line
        //is wanted there. Two bodies may hold the same empty ground)
        for(Body body : order) for(int[] r : body.holdWorld) for(int y = r[1]; y <= r[3]; y++) for(int x = r[0]; x <= r[2]; x++){
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
        for(Body body : order) for(Body.Part p : body.parts) hard += bridgeTerm(p);
        wire += wireTerm();
        //tiles about each part for its lines: each line, power, a drain takes a tile of its own against the part (a
        //tile holds one block), a liquid put out of one side only on that side (GenericCrafter.liquidOutputDirections);
        //what cannot be given its tile, the most of them matched (augmenting paths), is short
        List<int[]> wants = new ArrayList<>();
        List<Body.Part> wantPart = new ArrayList<>();
        for(Body body : order) for(Body.Part p : body.parts){
            addWants(p, wants, wantPart, false);
            for(int[] t : p.around()){
                if(!b.in(t[0], t[1])) continue;
                Body.Part o = partAt[b.idx(t[0], t[1])];
                if(o != null) hard += adjacent(p, o, t[2]);
            }
        }
        port += wants.size() - match(wants, wantPart, cover);
        port += payloadTerm();
        //(a free tile handed different things by the blocks about it takes only a carrier that faces away from all but
        //one of them: little use to any line; each thing more handed into it counts as a line end short)
        Map<Integer, Set<String>> handed = new HashMap<>();
        for(Body body : order) for(Body.Part p : body.parts){
            for(int[] t : p.around()){
                if(!b.in(t[0], t[1])) continue;
                int i = b.idx(t[0], t[1]);
                if(cover[i] != 0) continue;
                List<String> given = outOf(p, t[2]);
                if(given.isEmpty()) continue;
                handed.computeIfAbsent(i, k -> new HashSet<>()).addAll(given);
            }
        }
        for(Set<String> got : handed.values()) if(got.size() > 1) port += got.size() - 1;
        for(int i = 0; i < w * h; i++){
            float over = crowd[i] + cover[i] - 1f;
            if(over > 0f) crowding += over;
        }
        last[0] = hard;
        last[1] = wire;
        last[2] = port;
        last[3] = crowding;
        last[4] = yield;
        return wHard * hard + wWire * wire + wPort * port + wCrowd * crowding + wYield * yield;
    }

    List<Body> order(){
        List<Body> order = new ArrayList<>(bodies.size());
        for(Body x : bodies) if(!movable.contains(x)) order.add(x);
        order.addAll(movable);
        return order;
    }

    /** A bridge part reaching the first bridge of its kind ahead, of another body: as the game links them. */
    double bridgeTerm(Body.Part p){
        if(!(p.block instanceof mindustry.world.blocks.distribution.DirectionBridge)) return 0;
        int range = Carriers.range(p.block);
        for(int i = 1; i <= range; i++){
            int x = p.x0 + Geo.dx(p.rot) * i, y = p.y0 + Geo.dy(p.rot) * i;
            if(!b.in(x, y)) break;
            Body.Part o = partAt[b.idx(x, y)];
            if(o != null && o.block == p.block) return o.body != p.body ? hb(8, 1, p.body, o.body) : 0;
        }
        return 0;
    }

    /**
     * Who feeds whom, if fixed (a floor plan's ties: maker, taker, thing, weight): each tie's gap weighed, none for one
     * that touches; else each taker to its nearest maker.
     */
    public List<Object[]> ties;

    double tiesTerm(){
        double wire = 0;
        for(Object[] t : ties){
            Body.Part m = (Body.Part)t[0], k = (Body.Part)t[1];
            String res = (String)t[2];
            boolean liquid = mindustry.Vars.content.liquid(res) != null;
            if(Router.touches(m, k, res, liquid)) continue;
            int gap = gap(m, k) + 1;
            wire += (Float)t[3] * gap;
            spread(m, k, gap);
        }
        return wire;
    }

    /** The lines: each taker to its nearest maker and each maker to its nearest taker. */
    double wireTerm(){
        if(ties != null) return tiesTerm();
        double wire = 0;
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
        if(home != null && wTravel > 0f) for(Body body : movable){
            Body.Part m = body.main();
            wire += wTravel * (Math.abs((m.x0 + m.x1()) - (home.x0 + home.x1())) + Math.abs((m.y0 + m.y1()) - (home.y0 + home.y1()))) / 2.0;
            //(and a block that makes nothing put against the core takes a tile every line into it may want: a port lost)
            for(Body.Part p : body.parts){
                if(p.recipe != null) continue;
                int against = 0;
                for(int y = p.y0; y <= p.y1(); y++) for(int x = p.x0; x <= p.x1(); x++){
                    boolean nx = x == home.x0 - 1 || x == home.x1() + 1, ny = y == home.y0 - 1 || y == home.y1() + 1;
                    boolean inX = x >= home.x0 && x <= home.x1(), inY = y >= home.y0 && y <= home.y1();
                    if(nx && inY || ny && inX) against++;
                }
                wire += against * wPort / wWire;
            }
        }
        return wire;
    }

    /** The tiles a part wants for its lines, power, a drain (none for one it touches already). */
    void addWants(Body.Part p, List<int[]> wants, List<Body.Part> wantPart, boolean known){
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
        List<int[]> rs = known ? roles.getOrDefault(p, List.of()) : rolesOf(p);
        for(int[] r : rs){
            Group g = groups.get(r[0]);
            boolean makes = r[1] == 1, takes = r[2] == 1;
            boolean touching = false;
            if(takes) for(Body.Part m : g.makers) if(m != p && Router.touches(m, p, g.res, g.liquid)) touching = true;
            if(makes && !takes){
                touching = true;
                for(Body.Part t : g.takers) if(t != p && !Router.touches(p, t, g.res, g.liquid)) touching = false;
            }
            if(touching) continue;
            wants.add(new int[]{r[0], makes && !takes && g.payload ? 2 : makes && !takes ? 1 : 0});
            wantPart.add(p);
        }
    }

    /** Of each group, whether a part makes or takes its thing: {group, makes, takes}. */
    List<int[]> rolesOf(Body.Part p){
        List<int[]> out = new ArrayList<>();
        for(int gi = 0; gi < groups.size(); gi++){
            Group g = groups.get(gi);
            boolean makes = g.makers.contains(p), takes = g.takers.contains(p);
            if(makes || takes) out.add(new int[]{gi, makes ? 1 : 0, takes ? 1 : 0});
        }
        return out;
    }

    /**
     * What goes wrong where a part hands toward a neighbour of another body: what the core should not have, to the core;
     * into another body's port (a carrier on a module's edge) what is not the port's (mixed into its line); into a piece
     * of another body's own line what it does not carry; or from that line, taken for good by it.
     */
    double adjacent(Body.Part p, Body.Part o, int d){
        double hard = 0;
        List<String> given = outOf(p, d);
        for(String y : given) if(b.swallows(o, y)) hard += hb(9, 1, p.body, o.body);
        if(o.body != p.body && Board.isPortCarrier(o) && Board.pieceTakes(Board.asPiece(o), (d + 2) % 4, null, p)) for(String y : given) if(!y.equals(o.config)) hard += hb(10, 1, p.body, o.body);
        //(a piece of another body's own line against it: what it hands taken into that line, of another thing
        //(mixed); or what that line hands taken for good by it (as the router weighs a leak: what swallows it, a
        //port or a line of another thing; a machine that works by it takes only what it holds))
        if(o.body != p.body && o.piece != null){
            Board.Piece f = Body.fixed(o);
            int back = (d + 2) % 4;
            if(Board.pieceTakes(f, back, p.piece != null ? Body.fixed(p) : null, p.piece != null ? null : p)) for(String y : given) if(!y.equals(f.res) && (mindustry.Vars.content.item(y) != null ? f.itemCarrier() : f.liquidCarrier())){
                hard += hb(11, 1, p.body, o.body);
                if(why != null) why.add(p + " of " + p.body.label + " hands " + y + " into " + o.block.name + "@" + o.x0 + "," + o.y0 + " carrying " + f.res);
            }
            if(Router.has(Board.handsDirs(f), back) && swallows(p, f, d)){
                hard += hb(11, 1, p.body, o.body);
                if(why != null) why.add(o.block.name + "@" + o.x0 + "," + o.y0 + " hands " + f.res + " into " + p + " of " + p.body.label);
            }
        }
        return hard;
    }

    /** What a part hands toward each side, as it is turned now (worked out once a turn). */
    final Map<Body.Part, Object[]> outs = new IdentityHashMap<>();

    @SuppressWarnings("unchecked")
    List<String> outOf(Body.Part p, int d){
        Object[] o = outs.get(p);
        if(o == null || (Integer)o[0] != p.rot){
            List<String>[] by = new List[4];
            for(int k = 0; k < 4; k++) by[k] = Board.out(p, k);
            o = new Object[]{p.rot, by};
            outs.put(p, o);
        }
        return ((List<String>[])o[1])[d];
    }

    /**
     * Payload lines: a maker and a taker join only if the conveyors' squares fall in line with both; a payload leaves its
     * maker into the square ahead of its front.
     */
    double payloadTerm(){
        double port = 0;
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
        return port;
    }

    // ------------------------------------------------------------------ the quick count

    /**
     * What does not move is counted once a search (prepare): the ground as it leaves it (what covers each tile, the
     * lanes, the exclusive areas, the held ground), what goes wrong between such bodies alone, its parts that want
     * tiles, the tiles its parts hand things into. A count then adds what moves to that: the same terms, the same order.
     */
    boolean prepared;
    int[] baseCover, baseZone, zoneNow, laneStamp, solidAt, baseSolidHard, heldCount;
    boolean[] baseLane, laneNow;
    Body.Part[] basePartAt;
    Body[] baseLaneOf;
    double baseHard, baseBridgeHard, quickHard;
    boolean movableBridges;
    int stamp;
    /** Of what does not move: its parts that may want tiles; held tiles nothing stands on; the tiles its parts hand into, with what. */
    final List<Body.Part> staticWanting = new ArrayList<>();
    final List<Integer> heldEmpty = new ArrayList<>();
    final List<Object[]> staticHanded = new ArrayList<>();
    /** Of each part, the groups it makes or takes the thing of: {group, makes, takes}. */
    final Map<Body.Part, List<int[]>> roles = new IdentityHashMap<>();
    /** The parts that do not move standing on each tile (first the one counted as covering it). */
    List<Body.Part>[] staticParts;

    @SuppressWarnings("unchecked")
    void prepare(){
        int w = b.w, h = b.h, n = w * h;
        ensureArrays();
        if(baseCover == null || baseCover.length != n){
            baseCover = new int[n];
            baseZone = new int[n];
            zoneNow = new int[n];
            laneStamp = new int[n];
            solidAt = new int[n];
            baseSolidHard = new int[n];
            heldCount = new int[n];
            baseLane = new boolean[n];
            laneNow = new boolean[n];
            basePartAt = new Body.Part[n];
            baseLaneOf = new Body[n];
            staticParts = new List[n];
        }
        Arrays.fill(baseCover, 0);
        Arrays.fill(baseZone, 0);
        Arrays.fill(laneStamp, 0);
        Arrays.fill(solidAt, 0);
        Arrays.fill(baseSolidHard, 0);
        Arrays.fill(heldCount, 0);
        Arrays.fill(baseLane, false);
        Arrays.fill(basePartAt, null);
        Arrays.fill(baseLaneOf, null);
        Arrays.fill(staticParts, null);
        stamp = 0;
        staticWanting.clear();
        heldEmpty.clear();
        staticHanded.clear();
        roles.clear();
        List<Body> still = new ArrayList<>();
        for(Body x : bodies) if(!movable.contains(x)) still.add(x);
        double hard = 0;
        for(Body body : still) for(int i : body.clearTiles) markLane(baseLane, baseLaneOf, i, body);
        for(Body body : still) for(int[] r : body.clearWorld){
            for(int y = r[1]; y <= r[3]; y++) for(int x = r[0]; x <= r[2]; x++){
                if(!b.in(x, y)){
                    if(body.exclusive) hard += hb(0, 0.2, body, null);
                    continue;
                }
                int i = b.idx(x, y);
                markLane(baseLane, baseLaneOf, i, body);
                if(body.exclusive && baseZone[i]++ > 0) hard += hb(1, 1, body, null);
            }
        }
        for(Body body : still) for(Body.Part p : body.parts){
            if(!body.fixed && !(p.block.size == 1 && p.block.buildVisibility == mindustry.world.meta.BuildVisibility.sandboxOnly ? b.floor(p.x0, p.y0) : b.canStand(p.block, p.x0, p.y0, p.rot))) hard += hb(3, 1, body, null);
            for(int y = p.y0; y <= p.y1(); y++) for(int x = p.x0; x <= p.x1(); x++){
                if(!b.in(x, y)){
                    hard += hb(4, 1, body, null);
                    continue;
                }
                int i = b.idx(x, y);
                if(baseCover[i]++ > 0) hard += hb(5, 1, body, basePartAt[i] == null ? null : basePartAt[i].body);
                if(basePartAt[i] == null) basePartAt[i] = p;
                if(staticParts[i] == null) staticParts[i] = new ArrayList<>(1);
                staticParts[i].add(p);
                if(p.block.solid){
                    solidAt[i]++;
                    if(baseLane[i] || b.clear[i]){
                        double v = hb(6, 1, body, baseLaneOf[i]);
                        hard += v;
                        baseSolidHard[i] += (int)v;
                    }
                }
            }
        }
        //(held ground: what stands on it of another body counted; where nothing stands, to be taken unless something moves there)
        Set<Integer> empty = new LinkedHashSet<>();
        for(Body body : still) for(int[] r : body.holdWorld) for(int y = r[1]; y <= r[3]; y++) for(int x = r[0]; x <= r[2]; x++){
            if(!b.in(x, y)) continue;
            int i = b.idx(x, y);
            Body.Part o = basePartAt[i];
            if(o != null && o.body != body) hard += hb(7, 1, body, o.body);
            else if(o == null){
                heldCount[i]++;
                empty.add(i);
            }
        }
        heldEmpty.addAll(empty);
        movableBridges = false;
        for(Body body : movable) for(Body.Part p : body.parts) if(p.block instanceof mindustry.world.blocks.distribution.DirectionBridge) movableBridges = true;
        System.arraycopy(basePartAt, 0, partAt, 0, n);
        baseBridgeHard = 0;
        for(Body body : still) for(Body.Part p : body.parts) baseBridgeHard += bridgeTerm(p);
        //(between parts that do not move, what goes wrong where one hands toward the other)
        for(Body body : still) for(Body.Part p : body.parts) for(int[] t : p.around()){
            if(!b.in(t[0], t[1])) continue;
            Body.Part o = basePartAt[b.idx(t[0], t[1])];
            if(o != null) hard += adjacent(p, o, t[2]);
        }
        baseHard = hard;
        //(the parts that want tiles: of what does not move, those with a line, power, a drain or a margin)
        for(Body body : bodies) for(Body.Part p : body.parts){
            List<int[]> rs = rolesOf(p);
            if(!rs.isEmpty()) roles.put(p, rs);
        }
        for(Body body : still) for(Body.Part p : body.parts){
            boolean wants = roles.containsKey(p) || margin.getOrDefault(p, 0) > 0 || Power.needs(p.block) || Power.makes(p.block) || Board.mustDrain(p) != null;
            if(wants) staticWanting.add(p);
            for(int[] t : p.around()){
                if(!b.in(t[0], t[1])) continue;
                List<String> given = outOf(p, t[2]);
                if(!given.isEmpty()) staticHanded.add(new Object[]{b.idx(t[0], t[1]), given});
            }
        }
        prepared = true;
    }

    /** A tile what moves keeps clear: what is solid there of what does not move is now counted as on a lane of what moves. */
    void quickLane(int i, Body body){
        laneNow[i] = true;
        if(laneOf[i] == null || laneOf[i].standing && !body.standing) laneOf[i] = body;
        if(laneStamp[i] != stamp){
            laneStamp[i] = stamp;
            quickHard += solidAt[i] - baseSolidHard[i];
        }
    }

    double evaluateFast(){
        int w = b.w, h = b.h, n = w * h;
        stamp++;
        System.arraycopy(baseCover, 0, cover, 0, n);
        System.arraycopy(basePartAt, 0, partAt, 0, n);
        System.arraycopy(baseLane, 0, laneNow, 0, n);
        System.arraycopy(baseLaneOf, 0, laneOf, 0, n);
        System.arraycopy(baseZone, 0, zoneNow, 0, n);
        Arrays.fill(crowd, 0f);
        quickHard = baseHard;
        double wire = 0, port = 0, crowding = 0, yield = 0;
        for(Body body : movable) for(int i : body.clearTiles) quickLane(i, body);
        for(Body body : movable) for(int[] r : body.clearWorld){
            for(int y = r[1]; y <= r[3]; y++) for(int x = r[0]; x <= r[2]; x++){
                if(!b.in(x, y)){
                    if(body.exclusive) quickHard += hb(0, 0.2, body, null);
                    continue;
                }
                int i = b.idx(x, y);
                quickLane(i, body);
                if(body.exclusive && zoneNow[i]++ > 0) quickHard += hb(1, 1, body, null);
            }
        }
        for(var e : places.entrySet()){
            Places p = e.getValue();
            int k = index(e.getKey(), p);
            if(k < 0){
                quickHard += hb(2, 1, e.getKey(), null);
                continue;
            }
            yield += Math.max(0f, p.need - p.yields.get(k));
            for(int t : p.lanes.get(k)) quickLane(t, e.getKey());
        }
        for(Body body : movable) for(Body.Part p : body.parts){
            if(!body.fixed && !(p.block.size == 1 && p.block.buildVisibility == mindustry.world.meta.BuildVisibility.sandboxOnly ? b.floor(p.x0, p.y0) : b.canStand(p.block, p.x0, p.y0, p.rot))) quickHard += hb(3, 1, body, null);
            for(int y = p.y0; y <= p.y1(); y++) for(int x = p.x0; x <= p.x1(); x++){
                if(!b.in(x, y)){
                    quickHard += hb(4, 1, body, null);
                    continue;
                }
                int i = b.idx(x, y);
                if(cover[i]++ > 0) quickHard += hb(5, 1, body, partAt[i] == null ? null : partAt[i].body);
                if(partAt[i] == null) partAt[i] = p;
                if(p.block.solid && (laneNow[i] || b.clear[i])) quickHard += hb(6, 1, body, laneOf[i]);
            }
        }
        //(held ground of what does not move that something moved onto; held ground of what moves)
        for(int i : heldEmpty){
            if(partAt[i] != null) quickHard += heldCount[i];
            else cover[i] = 1;
        }
        for(Body body : movable) for(int[] r : body.holdWorld) for(int y = r[1]; y <= r[3]; y++) for(int x = r[0]; x <= r[2]; x++){
            if(!b.in(x, y)) continue;
            int i = b.idx(x, y);
            Body.Part o = partAt[i];
            if(o != null && o.body != body) quickHard += hb(7, 1, body, o.body);
            else if(o == null) cover[i] = 1;
        }
        if(movableBridges){
            for(Body body : bodies) for(Body.Part p : body.parts) quickHard += bridgeTerm(p);
        }else quickHard += baseBridgeHard;
        wire += wireTerm();
        List<int[]> wants = new ArrayList<>();
        List<Body.Part> wantPart = new ArrayList<>();
        for(Body.Part p : staticWanting) addWants(p, wants, wantPart, true);
        for(Body body : movable) for(Body.Part p : body.parts){
            addWants(p, wants, wantPart, true);
            //(what moves handing toward what is about it)
            for(int[] t : p.around()){
                if(!b.in(t[0], t[1])) continue;
                Body.Part o = partAt[b.idx(t[0], t[1])];
                if(o != null) quickHard += adjacent(p, o, t[2]);
            }
            //(and what does not move handing toward a tile only what moves stands on)
            for(int y = p.y0; y <= p.y1(); y++) for(int x = p.x0; x <= p.x1(); x++){
                if(!b.in(x, y)) continue;
                int s0 = b.idx(x, y);
                if(partAt[s0] != p) continue;
                for(int d = 0; d < 4; d++){
                    int ux = x - Geo.dx(d), uy = y - Geo.dy(d);
                    if(!b.in(ux, uy)) continue;
                    List<Body.Part> qs = staticParts[b.idx(ux, uy)];
                    if(qs != null) for(Body.Part q : qs) quickHard += adjacent(q, p, d);
                }
            }
        }
        port += wants.size() - match(wants, wantPart, cover);
        port += payloadTerm();
        Map<Integer, Set<String>> handed = new HashMap<>();
        for(Object[] e : staticHanded){
            int i = (Integer)e[0];
            if(cover[i] != 0) continue;
            @SuppressWarnings("unchecked") List<String> given = (List<String>)e[1];
            handed.computeIfAbsent(i, k -> new HashSet<>()).addAll(given);
        }
        for(Body body : movable) for(Body.Part p : body.parts){
            for(int[] t : p.around()){
                if(!b.in(t[0], t[1])) continue;
                int i = b.idx(t[0], t[1]);
                if(cover[i] != 0) continue;
                List<String> given = outOf(p, t[2]);
                if(given.isEmpty()) continue;
                handed.computeIfAbsent(i, k -> new HashSet<>()).addAll(given);
            }
        }
        for(Set<String> got : handed.values()) if(got.size() > 1) port += got.size() - 1;
        for(int i = 0; i < n; i++){
            float over = crowd[i] + cover[i] - 1f;
            if(over > 0f) crowding += over;
        }
        last[0] = quickHard;
        last[1] = wire;
        last[2] = port;
        last[3] = crowding;
        last[4] = yield;
        return wHard * quickHard + wWire * wire + wPort * port + wCrowd * crowding + wYield * yield;
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
        prepared = false;
        if(movable.isEmpty()){
            refresh();
            cost = evaluate();
            return;
        }
        //(what does not move counted once; the board's parts put down at the end, the count not asking them)
        prepare();
        try{
            anneal(steps, start, heat);
        }finally{
            prepared = false;
        }
    }

    void anneal(int steps, boolean start, double heat){
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
                    double c = evaluate();
                    if(c < bestC){
                        bestC = c;
                        bestP = new int[]{x, y, r};
                    }
                }
            }
            if(bestP != null) body.put(bestP[0], bestP[1], bestP[2]);
        }
        cost = evaluate();
        //(how far a move goes in cost, at the start: the temperature at which most moves are taken)
        double sum = 0;
        int n = 0;
        for(int i = 0; i < 60; i++){
            Body body = movable.get(rng.nextInt(movable.size()));
            int ox = body.ox, oy = body.oy, r = body.rot;
            putAnywhere(body);
            sum += Math.abs(evaluate() - cost);
            n++;
            body.put(ox, oy, r);
        }
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

    /** Whether a part takes for good what a piece of a line hands it from its side toward dir: a line or a port of another thing, what swallows it. */
    boolean swallows(Body.Part p, Board.Piece from, int dir){
        if(p.piece != null) return !from.res.equals(p.piece.res) && Board.pieceTakes(Body.fixed(p), dir, from, null);
        if(Board.isPortCarrier(p)) return !from.res.equals(p.config) && Board.pieceTakes(Board.asPiece(p), dir, from, null);
        return b.swallows(p, from.res);
    }

    /** The hard terms by kind, in the order they are counted in evaluate (for the report). */
    final double[] hardBy = new double[16];

    double hb(int k, double v, Body one, Body two){
        //(between what stands already, nothing to ask: it was laid out and checked, and it does not move)
        if((one == null || one.standing) && (two == null || two.standing)) return 0;
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
