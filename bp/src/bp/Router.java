package bp;

import bp.Board.*;
import mindustry.world.*;

import java.util.*;

/**
 * Lines laid between machines, all at once, by negotiated congestion (PathFinder, McMurchie and Ebeling 1995): every
 * line is laid by the cheapest way from its maker to each of its takers (A* over a tile and the direction it is entered
 * from), other lines' tiles and the leaks into or out of them allowed at a price that grows each round, and a tile that
 * stays fought over dearer for good, until no tile holds two blocks and nothing hands anything to a line of another thing.
 *
 * A line of items is ducts (armoured where a neighbour would hand it something), duct bridges over what is in the way,
 * and duct routers where it branches to another taker; or, for more than a duct carries, a surge conveyor line (its first
 * tile the only one that takes anything). A line of a liquid is reinforced conduits and bridge conduits. What is laid
 * is weighed by the ground it takes and what it costs, as the balance weighs machines.
 */
public final class Router{
    /** A line: one maker, its takers, one kind of carrier (0 ducts, 1 surge conveyors, 2 conduits). */
    public static final class Net{
        public final int id;
        public final String res;
        public final int cls;
        public final Body.Part from;
        public final List<Body.Part> to = new ArrayList<>();
        public final List<Float> flows = new ArrayList<>();
        public final List<Piece> pieces = new ArrayList<>();
        public final Set<Body.Part> unreached = new LinkedHashSet<>();
        /** A drain: a line of conduits to an empty tile it leaks onto (Building.moveLiquidForward), no taker. */
        public boolean drain;
        public boolean drained;

        public Net(int id, String res, int cls, Body.Part from){
            this.id = id;
            this.res = res;
            this.cls = cls;
            this.from = from;
        }

        public float flow(){
            float f = 0f;
            for(float x : flows) f += x;
            return f;
        }

        @Override
        public String toString(){
            return String.format("%s %s->%s %.2f/s (%s)", res, from, to, flow(), new String[]{"ducts", "surge", "conduits"}[cls]);
        }
    }

    final Board b;
    public final List<Net> nets;
    final float perTile, perItem;
    final List<Piece>[] at;
    final float[] hist;
    float pf = 0.5f;
    /** How far a bridge reaches, of the bridges the lines use (DirectionBridge.range): of items, of liquids. */
    int range, lrange;
    public int rounds;
    public int overused, leaks;
    /** The tiles still fought over (two blocks, or a leak) after the last round. */
    public final Set<Integer> fought = new HashSet<>();

    @SuppressWarnings("unchecked")
    public Router(Board b, List<Net> nets, float perTile, float perItem){
        this.b = b;
        this.nets = nets;
        this.perTile = perTile;
        this.perItem = perItem;
        at = new List[b.w * b.h];
        for(int i = 0; i < at.length; i++) at[i] = new ArrayList<>(1);
        hist = new float[b.w * b.h];
        itemBridges = new int[b.w * b.h];
        liquidBridges = new int[b.w * b.h];
        range = Carriers.range(blockOf(Kind.bridgeIn));
        lrange = Carriers.range(blockOf(Kind.lbridgeIn));
    }

    /** The block of a kind the lines use: the one the weights price lowest. */
    Block blockOf(Kind k){
        return blocks.computeIfAbsent(k, kk -> b.carriers.cheapest(kk.role, perTile, perItem));
    }

    final Map<Kind, Block> blocks = new EnumMap<>(Kind.class);

    Piece piece(Kind k, int x, int y, int rot, int net, String res){
        return new Piece(k, blockOf(k), x, y, rot, net, res);
    }

    float base(Kind k){
        if(k == Kind.leak) return perTile;
        return (float)Carriers.cost(blockOf(k), perTile, perItem);
    }

    final Map<Kind, Float> baseCost = new EnumMap<>(Kind.class);

    float cost(Kind k){
        return baseCost.computeIfAbsent(k, this::base);
    }

    // ------------------------------------------------------------------ leaks

    static boolean family(Piece a, Piece c){
        return a.itemCarrier() && c.itemCarrier() || a.liquidCarrier() && c.liquidCarrier();
    }

    static boolean has(int[] dirs, int d){
        for(int x : dirs) if(x == d) return true;
        return false;
    }

    static boolean bridgeFamily(Piece a, Piece c){
        boolean ai = a.kind == Kind.bridgeIn || a.kind == Kind.bridgeOut, ci = c.kind == Kind.bridgeIn || c.kind == Kind.bridgeOut;
        boolean al = a.kind == Kind.lbridgeIn || a.kind == Kind.lbridgeOut, cl = c.kind == Kind.lbridgeIn || c.kind == Kind.lbridgeOut;
        return ai && ci || al && cl;
    }

    static boolean isBridge(Piece a){
        return a.kind == Kind.bridgeIn || a.kind == Kind.bridgeOut || a.kind == Kind.lbridgeIn || a.kind == Kind.lbridgeOut;
    }

    /**
     * What goes wrong about a piece: something handed into it that is not its line's, its line's handed where it should
     * not go (another line, the core for what is no goal), a bridge that would reach it or that it would reach unmeant.
     * partner: for a bridge, the other end (its line's), else null.
     */
    /** What a part hands out, worked out once: liquids by the side they leave from (0-3), items (4). */
    final Map<Body.Part, List<String>[]> outsOf = new IdentityHashMap<>();

    @SuppressWarnings("unchecked")
    List<String>[] outs(Body.Part p){
        return outsOf.computeIfAbsent(p, k -> {
            List<String>[] o = new List[5];
            for(int d = 0; d < 4; d++) o[d] = Board.liquidsOut(k, d);
            o[4] = Board.itemsOut(k);
            return o;
        });
    }

    /** Whether a bridge end reaches another bridge of its kind ahead (DirectionBridge.findLink): it then takes from about it as a start. */
    boolean reachesAhead(Piece q){
        if(q.kind != Kind.bridgeOut && q.kind != Kind.lbridgeOut) return false;
        for(int i = 1; i <= Carriers.range(q.block); i++){
            int nx = q.x + Geo.dx(q.rot) * i, ny = q.y + Geo.dy(q.rot) * i;
            if(!b.in(nx, ny)) return false;
            int ni = b.idx(nx, ny);
            if((q.kind == Kind.bridgeOut ? itemBridges : liquidBridges)[ni] == 0) continue;
            for(Piece o : at[ni]) if(o != q && bridgeFamily(o, q)) return true;
        }
        return false;
    }

    /** A piece as it takes things: a bridge end that reaches another takes as a start does. */
    Piece taking(Piece q){
        if(!reachesAhead(q)) return q;
        Piece as = new Piece(q.kind == Kind.bridgeOut ? Kind.bridgeIn : Kind.lbridgeIn, q.block, q.x, q.y, q.rot, q.net, q.res);
        as.from = q.from;
        as.outs = q.outs;
        return as;
    }

    int leaks(Piece q0, Piece partner){
        int c = 0;
        if(q0.kind == Kind.leak) return 0;
        Piece q = taking(q0);
        for(int dir = 0; dir < 4; dir++){
            int nx = q.x + Geo.dx(dir), ny = q.y + Geo.dy(dir);
            if(!b.in(nx, ny)) continue;
            int n = b.idx(nx, ny);
            Body.Part p = b.partAt[n];
            if(p != null){
                if(Board.pieceTakes(q, dir, null, p)){
                    List<String> given = q.itemCarrier() ? outs(p)[4] : q.liquidCarrier() ? outs(p)[(dir + 2) % 4] : List.of();
                    for(String y : given) if(!y.equals(q.res)) c++;
                }
                if(has(Board.handsDirs(q), dir) && (q.outs & (1 << dir)) == 0 && q.itemCarrier() && Board.isCore(p) && !b.goals.contains(q.res)) c++;
            }
            for(Piece o : at[n]){
                if(o.net == q.net || !family(o, q)) continue;
                if(has(Board.handsDirs(o), (dir + 2) % 4) && Board.pieceTakes(q, dir, o, null) && !o.res.equals(q.res)) c++;
                if(has(Board.handsDirs(q), dir) && Board.pieceTakes(taking(o), (dir + 2) % 4, q, null) && !o.res.equals(q.res)) c++;
                //(a surge line hands its stack on to whatever surge conveyor it points at, taking or not)
                if(o.kind == Kind.surge && q.kind == Kind.surge && (o.role != 2 && o.x + Geo.dx(o.rot) == q.x && o.y + Geo.dy(o.rot) == q.y || q.role != 2 && q.x + Geo.dx(q.rot) == o.x && q.y + Geo.dy(q.rot) == o.y)) c++;
            }
        }
        q = q0;
        if(isBridge(q)){
            //(the first bridge of its kind along each way from it within range: one pointing at it reaches it, and this
            //one reaches the first ahead of it; that is meant only between the two ends of a hop, or from an end into
            //the next hop's start just ahead of it on its own line, a chain)
            for(int dir = 0; dir < 4; dir++){
                for(int i = 1; i <= Carriers.range(q.block); i++){
                    int nx = q.x + Geo.dx(dir) * i, ny = q.y + Geo.dy(dir) * i;
                    if(!b.in(nx, ny)) break;
                    int ni = b.idx(nx, ny);
                    if((q.kind == Kind.bridgeIn || q.kind == Kind.bridgeOut ? itemBridges : liquidBridges)[ni] == 0) continue;
                    Piece found = null;
                    for(Piece o : at[ni]) if(o != q && bridgeFamily(o, q)) found = o;
                    if(found == null) continue;
                    if(found != partner){
                        boolean qOut = q.kind == Kind.bridgeOut || q.kind == Kind.lbridgeOut, fOut = found.kind == Kind.bridgeOut || found.kind == Kind.lbridgeOut;
                        //(it reaches this one, which takes all it hands then: meant only where it is the end of a hop of
                        //this line just behind this one, the next hop's start, a chain. A bridge reaching another, of the
                        //same thing or not, takes its line's flow away from where it was laid to go)
                        boolean chainIn = fOut && found.net == q.net && i == 1 && found.x + Geo.dx(found.rot) == q.x && found.y + Geo.dy(found.rot) == q.y;
                        if(found.rot == (dir + 2) % 4 && !chainIn) c++;
                        //(this one reaches it)
                        boolean chainOut = qOut && found.net == q.net && i == 1 && dir == q.rot;
                        if(dir == q.rot && !chainOut) c++;
                    }
                    break;
                }
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ laying one line

    boolean[] own;

    /** Whether a piece can go on a tile: nothing of a body there, nothing of its own line, floor; a solid one not on a beam's way. */
    boolean open(int x, int y, boolean solid){
        if(!b.in(x, y)) return false;
        int i = b.idx(x, y);
        return b.partAt[i] == null && !(solid && b.clear[i]) && !own[i] && b.floor(x, y);
    }

    boolean solid(Kind k){
        return blockOf(k).solid;
    }

    /** Laying with what goes wrong forbidden, not priced (the last mending of a few lines). */
    boolean hard;

    float price(Piece q, Piece partner){
        int i = b.idx(q.x, q.y);
        if(hard && (!at[i].isEmpty() || leaks(q, partner) > 0)) return Float.POSITIVE_INFINITY;
        return cost(q.kind) + hist[i] + pf * (at[i].size() + leaks(q, partner));
    }

    int gap(int x, int y, Body.Part p){
        if(p == null) return 0;
        int dx = Math.max(0, Math.max(p.x0 - x, x - p.x1())), dy = Math.max(0, Math.max(p.y0 - y, y - p.y1()));
        return dx + dy;
    }

    /** States: tile, the direction it is entered by, and what feeds it (0 a carrier pointing into it, 1 something that hands
     * it out, 2 a surge conveyor before it on its line). */
    static int state(int idx, int d, int k){
        return (idx * 4 + d) * 3 + k;
    }

    float[] g;
    int[] par, mv, stamp;
    /** For each state, the tiles of the path to it folded into 64 bits: a tile whose bit is not set is not on the path. */
    long[] sig;

    static long bit(int tile){
        return 1L << ((tile * 0x9E3779B1) >>> 26);
    }
    int now;

    /** A move: what is put at the state's tile. type 0 duct, 1 armoured, 2 bridge (j, f), 3 surge, 4 conduit, 5 bridge conduit. */
    static int move(int type, int e, int j, int f){
        return type | e << 3 | j << 5 | f << 8;
    }

    void ensure(){
        int n = b.w * b.h * 12;
        if(g == null || g.length < n){
            g = new float[n];
            par = new int[n];
            mv = new int[n];
            stamp = new int[n];
            sig = new long[n];
        }
    }

    /** The pieces a move puts down from a state's tile. */
    List<Piece> pieces(Net net, int st, int m, boolean last){
        int idx = st / 12, d = st / 3 % 4, k = st % 3;
        int x = idx % b.w, y = idx / b.w;
        int type = m & 7, e = m >> 3 & 3, j = m >> 5 & 7, f = m >> 8 & 3;
        List<Piece> out = new ArrayList<>(2);
        switch(type){
            case 0, 1, 4 -> {
                Piece q = piece(type == 0 ? Kind.duct : type == 1 ? Kind.armored : Kind.conduit, x, y, e, net.id, net.res);
                q.from = d;
                q.outs = 1 << e;
                out.add(q);
            }
            case 3 -> {
                Piece q = piece(Kind.surge, x, y, e, net.id, net.res);
                q.from = d;
                q.outs = 1 << e;
                q.role = k == 1 ? 0 : last ? 2 : 1;
                out.add(q);
            }
            default -> {
                boolean liquid = type == 5;
                Piece in = piece(liquid ? Kind.lbridgeIn : Kind.bridgeIn, x, y, e, net.id, net.res);
                in.from = d;
                Piece end = piece(liquid ? Kind.lbridgeOut : Kind.bridgeOut, x + Geo.dx(e) * j, y + Geo.dy(e) * j, f, net.id, net.res);
                end.from = e;
                end.outs = 1 << f;
                out.add(in);
                out.add(end);
            }
        }
        return out;
    }

    /** The tile a move leads on to: {x, y, direction}. */
    int[] next(int st, int m){
        int idx = st / 12;
        int x = idx % b.w, y = idx / b.w;
        int type = m & 7, e = m >> 3 & 3, j = m >> 5 & 7, f = m >> 8 & 3;
        if(type == 2 || type == 5){
            int ux = x + Geo.dx(e) * j, uy = y + Geo.dy(e) * j;
            return new int[]{ux + Geo.dx(f), uy + Geo.dy(f), f};
        }
        return new int[]{x + Geo.dx(e), y + Geo.dy(e), e};
    }

    /** No bridge of this family between two tiles of a line (exclusive), nor ahead of an end within range. */
    boolean clearOfBridges(int x, int y, int dir, int from, int to, boolean liquid){
        for(int i = from; i <= to; i++){
            int nx = x + Geo.dx(dir) * i, ny = y + Geo.dy(dir) * i;
            if(!b.in(nx, ny)) return true;
            if((liquid ? liquidBridges : itemBridges)[b.idx(nx, ny)] > 0) return false;
        }
        return true;
    }

    /** The moves from a state, by the line's carrier and what feeds the tile. */
    List<Integer> moves(Net net, int st){
        int d = st / 3 % 4, k = st % 3;
        int back = (d + 2) % 4;
        List<Integer> out = new ArrayList<>();
        for(int e = 0; e < 4; e++){
            if(e == back) continue;
            switch(net.cls){
                case 0 -> {
                    out.add(move(0, e, 0, 0));
                    if(k == 0 || e == d) out.add(move(1, e, 0, 0));
                    for(int j = 2; j <= range; j++) for(int f = 0; f < 4; f++) if(f != (e + 2) % 4) out.add(move(2, e, j, f));
                }
                case 1 -> out.add(move(3, e, 0, 0));
                default -> {
                    if(k == 0 || e == d) out.add(move(4, e, 0, 0));
                    for(int j = 2; j <= lrange; j++) for(int f = 0; f < 4; f++) if(f != (e + 2) % 4) out.add(move(5, e, j, f));
                }
            }
        }
        return out;
    }

    boolean inPart(int x, int y, Body.Part p){
        return p.covers(x, y);
    }

    /**
     * Whether what a conduit hands on to takes from a liquid router put in its place: the next conduit of its line
     * points on the same way (a reinforced conduit takes from a block not a conduit only from behind), or it is a
     * bridge or what the line ends in.
     */
    boolean straightAfter(Net net, Piece p){
        int nx = p.x + Geo.dx(p.rot), ny = p.y + Geo.dy(p.rot);
        if(!b.in(nx, ny)) return false;
        for(Piece q : at[b.idx(nx, ny)]) if(q.net == net.id) return q.kind != Kind.conduit || q.rot == p.rot;
        return b.partAt[b.idx(nx, ny)] != null;
    }

    /**
     * Whether a tile is taken by the path that leads to a state (the pieces its moves put down): the search's states are
     * a tile and a direction, so a path could cross itself unless this is asked.
     */
    boolean onPath(int st, int tile){
        if((sig[st] & bit(tile)) == 0) return false;
        int cur = st;
        while(par[cur] >= 0){
            int parent = par[cur], m = mv[cur];
            int idx = parent / 12;
            if(idx == tile) return true;
            int type = m & 7;
            if(type == 2 || type == 5){
                int e = m >> 3 & 3, j = m >> 5 & 7;
                int x = idx % b.w + Geo.dx(e) * j, y = idx / b.w + Geo.dy(e) * j;
                if(b.idx(x, y) == tile) return true;
                //(and a bridge of its kind on the line between the ends of a hop of the path: that hop would reach it)
                int tx = tile % b.w, ty = tile / b.w, px = idx % b.w, py = idx / b.w;
                if(Geo.dx(e) != 0 ? ty == py && (tx - px) * Geo.dx(e) > 0 && (tx - px) * Geo.dx(e) < j : tx == px && (ty - py) * Geo.dy(e) > 0 && (ty - py) * Geo.dy(e) < j) return true;
            }
            cur = parent;
        }
        return false;
    }

    /** What leaking a liquid onto a tile weighs, as a tile of ground weighs 1 (perTile): the harm of its puddles. */
    public float wHarm = 100f;
    final Map<String, float[]> harm = new HashMap<>();

    /**
     * The harm of letting a liquid out onto a tile. On a liquid floor it cannot lie on (Liquid.canStayOn) it is gone at
     * once (Puddles.deposit): none. A liquid whose puddles take another out of what holds it (CellLiquid.spreadTarget:
     * neoplasm, water) grows a field about the tile: as much as its puddle hands its four neighbours (0.3 a tick each,
     * PuddleComp.update) over what a puddle loses (1 - viscosity over 5 a tick), in tiles; each block in the field that
     * holds the target liquid is harmed.
     */
    float harmAt(String res, int x, int y){
        float[] h = harm.computeIfAbsent(res, k -> {
            float[] out = new float[b.w * b.h];
            mindustry.type.Liquid l = mindustry.Vars.content.liquid(k);
            if(!(l instanceof mindustry.type.CellLiquid cl) || cl.spreadTarget == null) return out;
            float area = 4f * 0.3f / ((1f - l.viscosity) / 5f);
            int r = 0;
            while(2 * r * r + 2 * r + 1 < area) r++;
            String target = cl.spreadTarget.name;
            List<Body.Part> holders = new ArrayList<>();
            Set<Body.Part> seenParts = new HashSet<>();
            for(Body.Part p : b.partAt) if(p != null && seenParts.add(p)){
                boolean holds = target.equals(p.config) || p.recipe != null && (p.recipe.in.containsKey(target) || p.recipe.out.containsKey(target) || p.recipe.fixedIn.containsKey(target));
                if(holds) holders.add(p);
            }
            for(int yy = 0; yy < b.h; yy++) for(int xx = 0; xx < b.w; xx++){
                int n = 0;
                for(Body.Part p : holders) if(gap(xx, yy, p) <= r) n++;
                out[b.idx(xx, yy)] = n * wHarm;
            }
            return out;
        });
        int i = b.idx(x, y);
        var f = b.floorAt[i];
        mindustry.type.Liquid l = mindustry.Vars.content.liquid(res);
        if(f.isLiquid && f.liquidDrop != null && l != null && !l.canStayOn.contains(f.liquidDrop)) return 0f;
        return h[i];
    }

    /** An empty tile a conduit may leak onto: no block, not solid ground, nothing of a line on it. */
    boolean leakable(int x, int y){
        int i = b.idx(x, y);
        return b.partAt[i] == null && !b.solidGround[i] && !own[i];
    }

    /** Lays the line to one taker (null: a drain's empty tile): true if laid. */
    boolean lay(Net net, Body.Part sink){
        ensure();
        now++;
        int w = b.w;
        PriorityQueue<long[]> open = new PriorityQueue<>((p, q) -> Double.compare(Double.longBitsToDouble(p[0]), Double.longBitsToDouble(q[0])));
        float hmin = net.cls == 0 ? Math.min(cost(Kind.duct), 2f * cost(Kind.bridgeIn) / (range + 1)) : net.cls == 1 ? cost(Kind.surge) : Math.min(cost(Kind.conduit), 2f * cost(Kind.lbridgeIn) / (lrange + 1));
        //starts: the tiles about the maker it hands the thing to
        Body.Part from = net.from;
        for(int[] t : from.around()){
            if(!open(t[0], t[1], false)) continue;
            List<String> gives = net.cls == 2 ? Board.liquidsOut(from, t[2]) : Board.itemsOut(from);
            if(!gives.contains(net.res)) continue;
            push(open, state(b.idx(t[0], t[1]), t[2], 1), 0f, -1, 0, gap(t[0], t[1], sink) * hmin);
        }
        //and the line's ducts so far, turned to routers: their sides (ducts only)
        float bestGoal = Float.MAX_VALUE;
        int goalSt = -1, goalMv = -1, goalTap = -1;
        //(a line's ducts so far turned to routers, its conduits to liquid routers: their sides; where a router hands on
        //ahead to what takes from it, a straight run)
        if(net.cls != 1 && sink != null) for(int pi = 0; pi < net.pieces.size(); pi++){
            Piece p = net.pieces.get(pi);
            Kind tapKind = net.cls == 0 ? Kind.router : Kind.lrouter;
            boolean router = p.kind == tapKind;
            if(!router && !(net.cls == 0 ? (p.kind == Kind.duct || p.kind == Kind.armored) && p.from == p.rot : p.kind == Kind.conduit && p.from == p.rot && straightAfter(net, p))) continue;
            for(int side : new int[]{(p.rot + 1) % 4, (p.rot + 3) % 4}){
                if((p.outs & (1 << side)) != 0) continue;
                int nx = p.x + Geo.dx(side), ny = p.y + Geo.dy(side);
                Piece r = piece(tapKind, p.x, p.y, p.rot, net.id, net.res);
                r.from = p.from;
                r.outs = p.outs | (1 << side);
                int tapLeaks = leaks(r, null);
                if(hard && tapLeaks > 0) continue;
                float tap = (router ? 0f : cost(tapKind) - cost(p.kind)) + pf * tapLeaks;
                if(b.in(nx, ny) && inPart(nx, ny, sink)){
                    if(tap < bestGoal){
                        bestGoal = tap;
                        goalTap = pi * 4 + side;
                        goalSt = -1;
                    }
                    continue;
                }
                if(!open(nx, ny, false)) continue;
                push(open, state(b.idx(nx, ny), side, 1), tap, -2 - (pi * 4 + side), 0, gap(nx, ny, sink) * hmin);
            }
        }
        while(!open.isEmpty()){
            long[] top = open.poll();
            double f = Double.longBitsToDouble(top[0]);
            int st = (int)top[1];
            if(f >= bestGoal) break;
            if(stamp[st] != now || g[st] < (float)top[2] - 1e-4f) continue;
            int idx = st / 12;
            int x = idx % w, y = idx / w;
            for(int m : moves(net, st)){
                int type = m & 7, e = m >> 3 & 3, j = m >> 5 & 7, ff = m >> 8 & 3;
                int[] nx = next(st, m);
                //(a drain ends in a conduit that leaks, Conduit.leaks: a bridge's end hands on without leaking, moveLiquidForward(false))
                boolean goal = b.in(nx[0], nx[1]) && (sink == null ? leakable(nx[0], nx[1]) && type == 4 && blockOf(Kind.conduit) instanceof mindustry.world.blocks.liquid.Conduit cd && cd.leaks : inPart(nx[0], nx[1], sink));
                //(a surge line of one tile loads nothing)
                if(type == 3 && st % 3 == 1 && goal) continue;
                if(type == 2 || type == 5){
                    int ux = x + Geo.dx(e) * j, uy = y + Geo.dy(e) * j;
                    if(!open(x, y, solid(type == 2 ? Kind.bridgeIn : Kind.lbridgeIn)) || !open(ux, uy, solid(type == 2 ? Kind.bridgeOut : Kind.lbridgeOut))) continue;
                    if(!clearOfBridges(x, y, e, 1, j - 1, type == 5)) continue;
                }
                if(!goal && !open(nx[0], nx[1], false)) continue;
                List<Piece> ps = pieces(net, st, m, goal);
                //(not over the path itself)
                boolean crosses = false;
                for(Piece q : ps) if(onPath(st, b.idx(q.x, q.y))) crosses = true;
                if(!goal && onPath(st, b.idx(nx[0], nx[1]))) crosses = true;
                if(crosses) continue;
                //(first what it costs without its leaks, a bound below: most moves end there, a better way to where they
                //lead being known already)
                float low = 0f;
                for(Piece q : ps){
                    int qi = b.idx(q.x, q.y);
                    low += cost(q.kind) + hist[qi] + pf * at[qi].size();
                }
                if(!hard){
                    float lowG = g[st] + low;
                    if(lowG >= bestGoal) continue;
                    if(!goal){
                        int ns0 = state(b.idx(nx[0], nx[1]), nx[2], type == 3 ? 2 : 0);
                        if(stamp[ns0] == now && g[ns0] <= lowG) continue;
                    }
                }
                float c = 0f;
                for(Piece q : ps) c += price(q, ps.size() == 2 ? ps.get(ps.size() - 1 - ps.indexOf(q)) : null);
                if(c == Float.POSITIVE_INFINITY) continue;
                float ng = g[st] + c;
                if(goal && sink == null) ng += harmAt(net.res, nx[0], nx[1]);
                if(goal){
                    if(ng < bestGoal){
                        bestGoal = ng;
                        goalSt = st;
                        goalMv = m;
                        goalTap = -1;
                    }
                    continue;
                }
                int k2 = type == 3 ? 2 : 0;
                int ns = state(b.idx(nx[0], nx[1]), nx[2], k2);
                push(open, ns, ng, st, m, gap(nx[0], nx[1], sink) * hmin);
            }
        }
        if(bestGoal == Float.MAX_VALUE) return false;
        //the line, back from the taker
        List<Piece> laid = new ArrayList<>();
        int tapRef;
        if(goalSt == -1){
            tapRef = goalTap;
        }else{
            int st = goalSt, m = goalMv;
            boolean last = true;
            while(true){
                List<Piece> ps = pieces(net, st, m, last);
                last = false;
                laid.addAll(0, ps);
                int p = par[st];
                if(p < 0){
                    tapRef = p == -1 ? -1 : -2 - p;
                    break;
                }
                m = mv[st];
                st = p;
            }
        }
        if(tapRef >= 0){
            Piece p = net.pieces.get(tapRef / 4);
            unplace(p);
            p.kind = net.cls == 0 ? Kind.router : Kind.lrouter;
            p.block = blockOf(p.kind);
            p.outs |= 1 << (tapRef % 4);
            place(p);
        }
        for(Piece q : laid){
            net.pieces.add(q);
            place(q);
        }
        //(what of the line now goes wrong with the rest of itself, a bridge reaching one of its own unmeant: dearer next round)
        for(Piece q : laid){
            if(selfLeaks(net, q) > 0) hist[b.idx(q.x, q.y)] += 1f;
        }
        if(sink == null && !laid.isEmpty()){
            //(the tile it leaks onto, kept empty)
            Piece last = laid.get(laid.size() - 1);
            Piece leak = new Piece(Kind.leak, null, last.x + Geo.dx(last.rot), last.y + Geo.dy(last.rot), 0, net.id, net.res);
            net.pieces.add(leak);
            place(leak);
        }
        return true;
    }

    void push(PriorityQueue<long[]> open, int st, float ng, int parent, int m, float h){
        if(stamp[st] == now && g[st] <= ng) return;
        stamp[st] = now;
        g[st] = ng;
        par[st] = parent;
        mv[st] = m;
        sig[st] = parent >= 0 ? sig[parent] | moveBits(parent, m) : 0L;
        open.add(new long[]{Double.doubleToLongBits(ng + h), st, Double.doubleToLongBits(ng) == 0 ? 0 : 0});
    }

    /** The bits of the tiles a move from a state takes: its pieces and, for a bridge, the tiles its span passes over. */
    long moveBits(int st, int m){
        int idx = st / 12;
        long bits = bit(idx);
        int type = m & 7;
        if(type == 2 || type == 5){
            int e = m >> 3 & 3, j = m >> 5 & 7;
            int x = idx % b.w, y = idx / b.w;
            for(int i = 1; i <= j; i++){
                int nx = x + Geo.dx(e) * i, ny = y + Geo.dy(e) * i;
                if(b.in(nx, ny)) bits |= bit(b.idx(nx, ny));
            }
        }
        return bits;
    }

    /** Bridges of items and of liquids on each tile, counted, for finding them along a way fast. */
    int[] itemBridges, liquidBridges;

    void place(Piece q){
        int i = b.idx(q.x, q.y);
        at[i].add(q);
        own[i] = true;
        if(q.kind == Kind.bridgeIn || q.kind == Kind.bridgeOut) itemBridges[i]++;
        if(q.kind == Kind.lbridgeIn || q.kind == Kind.lbridgeOut) liquidBridges[i]++;
    }

    void unplace(Piece q){
        int i = b.idx(q.x, q.y);
        if(at[i].remove(q)){
            if(q.kind == Kind.bridgeIn || q.kind == Kind.bridgeOut) itemBridges[i]--;
            if(q.kind == Kind.lbridgeIn || q.kind == Kind.lbridgeOut) liquidBridges[i]--;
        }
    }

    void ripUp(Net net){
        for(Piece q : net.pieces) unplace(q);
        net.pieces.clear();
    }

    // ------------------------------------------------------------------ all lines

    /** Lays every line, rounds of rip-up and re-lay, until nothing is fought over or so many rounds: true if clean. */
    /** Starts from what an earlier laying found fought over (its history), the placing having moved only a little. */
    public void carry(float[] earlier){
        if(earlier != null && earlier.length == hist.length) System.arraycopy(earlier, 0, hist, 0, hist.length);
    }

    public float[] history(){
        return hist;
    }

    /** Whether a line is in what is fought over: a piece on or against such a tile, or a taker not reached. */
    boolean involved(Net net){
        if(!net.unreached.isEmpty() || net.drain && !net.drained) return true;
        for(Piece q : net.pieces){
            if(fought.contains(b.idx(q.x, q.y))) return true;
            for(int d = 0; d < 4; d++){
                int nx = q.x + Geo.dx(d), ny = q.y + Geo.dy(d);
                if(b.in(nx, ny) && fought.contains(b.idx(nx, ny))) return true;
            }
        }
        return false;
    }

    public boolean run(int maxRounds){
        own = new boolean[b.w * b.h];
        int bestBad = Integer.MAX_VALUE, since = 0;
        for(rounds = 1; rounds <= maxRounds; rounds++){
            for(Net net : nets){
                //(after the first rounds, only the lines in what is fought over are laid again)
                if(rounds > 3 && !involved(net)) continue;
                ripUp(net);
                Arrays.fill(own, false);
                net.unreached.clear();
                //(takers nearest the maker first; one the maker touches and hands to needs no line)
                List<Body.Part> order = new ArrayList<>(net.to);
                order.sort(Comparator.comparingInt(p -> gap(p, net.from)));
                for(Body.Part sink : order){
                    if(touches(net.from, sink, net.res, net.cls == 2)) continue;
                    if(!lay(net, sink)) net.unreached.add(sink);
                }
                if(net.drain) net.drained = lay(net, null);
            }
            //what is still fought over
            overused = 0;
            leaks = 0;
            Set<Integer> bad = new HashSet<>();
            for(int i = 0; i < at.length; i++) if(at[i].size() > 1){
                overused++;
                bad.add(i);
            }
            for(Net net : nets) for(Piece q : net.pieces){
                int l = leaks(q, partner(net, q));
                if(l > 0){
                    leaks += l;
                    bad.add(b.idx(q.x, q.y));
                }
            }
            int unreached = 0;
            for(Net net : nets) unreached += net.unreached.size() + (net.drain && !net.drained ? 1 : 0);
            fought.clear();
            fought.addAll(bad);
            if(bad.isEmpty() && unreached == 0) return true;
            //(no better for a while: what is left is the placing's to mend)
            int nowBad = bad.size() + unreached;
            if(nowBad < bestBad){
                bestBad = nowBad;
                since = rounds;
            }else if(rounds - since >= 40) break;
            if(Boolean.getBoolean("bp.debug") && (rounds == maxRounds || rounds - since >= 40)) for(int i = 0; i < at.length; i++) if(at[i].size() > 1){
                StringBuilder who = new StringBuilder();
                for(Piece o : at[i]) who.append(" ").append(o).append("/").append(o.res).append("#").append(o.net);
                int x = i % b.w, y = i / b.w;
                StringBuilder around = new StringBuilder();
                for(int d = 0; d < 4; d++){
                    int nx = x + Geo.dx(d), ny = y + Geo.dy(d);
                    if(!b.in(nx, ny)) continue;
                    Body.Part p = b.partAt[b.idx(nx, ny)];
                    around.append(" ").append(d).append(":").append(p != null ? p.toString() : at[b.idx(nx, ny)].isEmpty() ? (b.floor(nx, ny) ? "free" : "wall") : at[b.idx(nx, ny)].get(0).toString());
                }
                System.out.println("  shared " + x + "," + y + " hist " + hist[i] + ":" + who + " | about:" + around);
            }
            if(Boolean.getBoolean("bp.debug") && (rounds == maxRounds || rounds - since >= 40)) for(Net net : nets) for(Piece q : net.pieces){
                int l = leaks(q, partner(net, q));
                if(l == 0) continue;
                StringBuilder why = new StringBuilder();
                for(int dir = 0; dir < 4; dir++){
                    int nx = q.x + Geo.dx(dir), ny = q.y + Geo.dy(dir);
                    if(!b.in(nx, ny)) continue;
                    Body.Part p = b.partAt[b.idx(nx, ny)];
                    if(p != null) why.append(" ").append(dir).append(":").append(p).append(Board.liquidsOut(p, (dir + 2) % 4)).append(Board.itemsOut(p));
                    for(Piece o : at[b.idx(nx, ny)]) why.append(" ").append(dir).append(":").append(o).append("/").append(o.res).append("#").append(o.net);
                }
                System.out.println("  leak " + l + " at " + q + " (" + q.res + " #" + q.net + " role " + q.role + "):" + why);
            }
            //(the price of sharing grows slowly and stops growing, so that what has been fought over, the history, comes to
            //decide: PathFinder's present and history costs)
            for(int i : bad) hist[i] += 1f;
            pf = Math.min(pf * 1.3f, 50f);
        }
        rounds = Math.min(rounds, maxRounds);
        return mend();
    }

    /** Counts what is still wrong: tiles with two blocks, leaks, takers not reached; fills fought. */
    int count(){
        overused = 0;
        leaks = 0;
        fought.clear();
        for(int i = 0; i < at.length; i++) if(at[i].size() > 1){
            overused++;
            fought.add(i);
        }
        for(Net net : nets) for(Piece q : net.pieces){
            int l = leaks(q, partner(net, q));
            if(l > 0){
                leaks += l;
                fought.add(b.idx(q.x, q.y));
            }
        }
        int unreached = 0;
        for(Net net : nets) unreached += net.unreached.size() + (net.drain && !net.drained ? 1 : 0);
        return overused + leaks + unreached;
    }

    /**
     * The last mending: each line still in what is fought over taken up and laid again with every tile of another line
     * and every leak forbidden, kept if it is laid whole, put back as it was if not; a few passes.
     */
    boolean mend(){
        int bad = count();
        for(int pass = 0; pass < 3 && bad > 0; pass++){
            for(Net net : nets){
                if(!involved(net)) continue;
                List<Piece> old = new ArrayList<>(net.pieces);
                Set<Body.Part> oldUnreached = new LinkedHashSet<>(net.unreached);
                boolean oldDrained = net.drained;
                ripUp(net);
                Arrays.fill(own, false);
                net.unreached.clear();
                hard = true;
                boolean whole = true;
                List<Body.Part> order = new ArrayList<>(net.to);
                order.sort(Comparator.comparingInt(p -> gap(p, net.from)));
                for(Body.Part sink : order){
                    if(touches(net.from, sink, net.res, net.cls == 2)) continue;
                    if(!lay(net, sink)){
                        whole = false;
                        break;
                    }
                }
                if(whole && net.drain) whole = net.drained = lay(net, null);
                hard = false;
                if(!whole){
                    ripUp(net);
                    for(Piece q : old){
                        net.pieces.add(q);
                        place(q);
                    }
                    net.unreached.clear();
                    net.unreached.addAll(oldUnreached);
                    net.drained = oldDrained;
                }
                bad = count();
                if(bad == 0) break;
            }
        }
        return bad == 0;
    }

    /** Bridge reaches between a piece and the rest of its own line (leaks() asks the same of all lines). */
    int selfLeaks(Net net, Piece q){
        return isBridge(q) ? leaks(q, partner(net, q)) : 0;
    }

    static Piece partner(Net net, Piece q){
        if(!isBridge(q)) return null;
        int i = net.pieces.indexOf(q);
        if(q.kind == Kind.bridgeIn || q.kind == Kind.lbridgeIn) return i + 1 < net.pieces.size() ? net.pieces.get(i + 1) : null;
        return i > 0 ? net.pieces.get(i - 1) : null;
    }

    int gap(Body.Part a, Body.Part c){
        int dx = Math.max(0, Math.max(c.x0 - a.x1() - 1, a.x0 - c.x1() - 1)), dy = Math.max(0, Math.max(c.y0 - a.y1() - 1, a.y0 - c.y1() - 1));
        return dx + dy;
    }

    /** Whether a maker hands this straight to a taker it touches. */
    static boolean touches(Body.Part from, Body.Part to, String res, boolean liquid){
        for(int[] t : from.around()){
            if(!to.covers(t[0], t[1])) continue;
            List<String> gives = liquid ? Board.liquidsOut(from, t[2]) : Board.itemsOut(from);
            if(gives.contains(res)) return true;
        }
        return false;
    }

    /** The blocks laid (not the tiles kept empty for leaks). */
    public List<Piece> all(){
        List<Piece> out = new ArrayList<>();
        for(Net n : nets) for(Piece q : n.pieces) if(q.block != null) out.add(q);
        return out;
    }
}
