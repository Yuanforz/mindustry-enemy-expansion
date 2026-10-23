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
        /** Takers reached by joining another line of the same thing to the same taker: the piece joined, by taker. */
        public final Map<Body.Part, Piece> joins = new HashMap<>();
        /** A drain: a line of conduits to an empty tile it leaks onto (Building.moveLiquidForward), no taker. */
        public boolean drain;
        /** Payloads the line must hold on its way (each payload conveyor holds one): for a taker that takes a batch at once. */
        public int buffer;
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
            return String.format("%s %s->%s %.3f/s (%s)", res, from, to, flow(), new String[]{"ducts", "surge", "conduits", "payloads, buffer " + buffer}[cls]);
        }
    }

    /**
     * A line that stands already (a base grown): the thing it carries, its takers, how much it carries now (its own and
     * what lines run on into it) and at most (its slowest carrier). A new line may run on into it as into a line laid.
     */
    public static final class Standing{
        public final int id;
        public final String res;
        public final List<Body.Part> to = new ArrayList<>();
        public float load, cap;

        public Standing(int id, String res){
            this.id = id;
            this.res = res;
        }

        /** Of items, to the core alone. */
        public boolean coreBound(){
            if(to.isEmpty() || mindustry.Vars.content.item(res) == null) return false;
            for(Body.Part t : to) if(!Board.isCore(t)) return false;
            return true;
        }

        /** Whether it carries to that taker alone. */
        public boolean onlyTo(Body.Part sink){
            return to.size() == 1 && to.get(0) == sink;
        }
    }

    /** A line of items to the core alone: the core takes every item, so on such a line any item goes where it is to go. */
    static boolean coreBound(Net n){
        if(n == null || n.drain || n.to.isEmpty() || n.cls == 3 || mindustry.Vars.content.item(n.res) == null) return false;
        for(Body.Part t : n.to) if(!Board.isCore(t)) return false;
        return true;
    }

    boolean coreBound(Piece p){
        if(p.line != null) return p.line.coreBound();
        if(p.fixed) return false;
        return coreBound(byId.get(p.net));
    }

    /**
     * Whether what one piece hands another goes where it was to go: any items on lines to the core alone; else the same
     * thing, and where a line that stands is one of them, to the same takers (what one hands a line that stands goes on
     * where that one goes).
     */
    boolean same(Piece a, Piece c){
        if(a.itemCarrier() && c.itemCarrier() && coreBound(a) && coreBound(c)) return true;
        if(!a.res.equals(c.res)) return false;
        if(a.line == null && c.line == null) return true;
        List<Body.Part> ta = takers(a), tc = takers(c);
        return ta != null && tc != null && ta.size() == tc.size() && ta.containsAll(tc);
    }

    List<Body.Part> takers(Piece p){
        if(p.line != null) return p.line.to;
        Net n = p.fixed ? null : byId.get(p.net);
        return n == null ? null : n.to;
    }

    final Board b;
    public final List<Net> nets;
    final Weights w;
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
    public Router(Board b, List<Net> nets, Weights w){
        this.b = b;
        this.nets = nets;
        this.w = w;
        at = new List[b.w * b.h];
        for(int i = 0; i < at.length; i++) at[i] = new ArrayList<>(1);
        hist = new float[b.w * b.h];
        itemBridges = new int[b.w * b.h];
        liquidBridges = new int[b.w * b.h];
        range = Carriers.range(blockOf(Kind.bridgeIn));
        lrange = Carriers.range(blockOf(Kind.lbridgeIn));
        //(the pieces of lines that stand as parts of bodies, a module's own lines: standing among the pieces laid, as they
        //are, so that what is laid about them is weighed by what they hand and take; bridges that stand as parts besides,
        //in the way of every bridge of their kind)
        partBridge = new Body.Part[b.w * b.h];
        Block ib = blockOf(Kind.bridgeIn), lb = blockOf(Kind.lbridgeIn);
        Set<Body.Part> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for(int i = 0; i < at.length; i++){
            Body.Part p = b.partAt[i];
            if(p == null || !seen.add(p)) continue;
            if(p.piece != null){
                Piece f = Body.fixed(p);
                at[b.idx(f.x, f.y)].add(f);
                if(f.kind == Kind.bridgeIn || f.kind == Kind.bridgeOut) itemBridges[b.idx(f.x, f.y)]++;
                if(f.kind == Kind.lbridgeIn || f.kind == Kind.lbridgeOut) liquidBridges[b.idx(f.x, f.y)]++;
                continue;
            }
            if(p.block.size != 1) continue;
            if(p.block == ib){
                partBridge[i] = p;
                itemBridges[i]++;
            }else if(p.block == lb){
                partBridge[i] = p;
                liquidBridges[i]++;
            }
        }
    }

    final Body.Part[] partBridge;

    /** The block of a kind the lines use: the one the weights price lowest. */
    Block blockOf(Kind k){
        return blocks.computeIfAbsent(k, kk -> b.carriers.cheapest(kk.role, w));
    }

    final Map<Kind, Block> blocks = new EnumMap<>(Kind.class);

    Piece piece(Kind k, int x, int y, int rot, int net, String res){
        return new Piece(k, blockOf(k), x, y, rot, net, res);
    }

    float base(Kind k){
        if(k == Kind.leak) return w.tile();
        Block bl = blockOf(k);
        //(a carrier of what cannot be had now is not laid: a base still growing has not every item)
        if(bl != null && b.lacking != null) for(var st : bl.requirements) if(b.lacking.contains(st.item.name)) return Float.POSITIVE_INFINITY;
        return (float)w.cost(bl);
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
            for(int d = 0; d < 4; d++) o[d] = Board.out(k, d);
            o[4] = List.of();
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
            if(partBridge[ni] != null && partBridge[ni].block == q.block) return true;
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
        if(q0.kind == Kind.leak || q0.kind == Kind.payload) return 0;
        Piece q = taking(q0);
        for(int dir = 0; dir < 4; dir++){
            int nx = q.x + Geo.dx(dir), ny = q.y + Geo.dy(dir);
            if(!b.in(nx, ny)) continue;
            int n = b.idx(nx, ny);
            Body.Part p = b.partAt[n];
            //(a machine of the final form still to come, on its held ground: as if it stood there)
            Body.Part ghost = p == null && b.ghostAt != null ? b.ghostAt[n] : null;
            if(ghost != null && (q.itemCarrier() || q.liquidCarrier())){
                if(Board.pieceTakes(q, dir, null, ghost)){
                    for(String y : outs(ghost)[(dir + 2) % 4]){
                        boolean item = mindustry.Vars.content.item(y) != null;
                        if(item ? q.itemCarrier() : q.liquidCarrier()) c++;
                    }
                }
                if(has(Board.handsDirs(q), dir) && (q.outs & (1 << dir)) == 0 && b.partTakes(ghost, q.res)) c++;
            }
            if(p != null){
                if(Board.pieceTakes(q, dir, null, p)){
                    for(String y : outs(p)[(dir + 2) % 4]){
                        boolean item = mindustry.Vars.content.item(y) != null;
                        if((item ? q.itemCarrier() : q.liquidCarrier()) && !y.equals(q.res)) c++;
                    }
                }
                if(has(Board.handsDirs(q), dir) && (q.outs & (1 << dir)) == 0 && (q.itemCarrier() || q.liquidCarrier())){
                    //(handed, unmeant, to what takes it there for good: what swallows it; a module's port of another thing,
                    //into the port's line, mixed. A machine that works by it takes only what it holds, then no more: the
                    //flow comes back to the line)
                    if(b.swallows(p, q.res)) c++;
                    else if(Board.isPortCarrier(p) && !q.res.equals(p.config) && Board.pieceTakes(Board.asPiece(p), (dir + 2) % 4, q, null)) c++;
                }
            }
            for(Piece o : at[n]){
                if(o.net == q.net || !family(o, q)) continue;
                //(a body's own piece hands and takes nothing of a line laid about it, of its thing or another: its
                //lines are its own. A line that stands is a line as any other)
                boolean other = o.fixed && o.line == null || !same(o, q);
                if(has(Board.handsDirs(o), (dir + 2) % 4) && Board.pieceTakes(q, dir, o, null) && other) c++;
                if(has(Board.handsDirs(q), dir) && Board.pieceTakes(taking(o), (dir + 2) % 4, q, null) && other) c++;
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
                    if(found == null){
                        //(a body's own bridge: never this line's; it reaching this one, or this one it, is wrong)
                        Body.Part pb = partBridge[ni];
                        if(pb == null || pb.block != q.block) continue;
                        if(pb.rot == (dir + 2) % 4) c++;
                        if(dir == q.rot) c++;
                        break;
                    }
                    if(found != partner){
                        boolean qOut = q.kind == Kind.bridgeOut || q.kind == Kind.lbridgeOut, fOut = found.kind == Kind.bridgeOut || found.kind == Kind.lbridgeOut;
                        //(it reaches this one, which takes all it hands then: meant only where it is the end of a hop of
                        //this line just behind this one, the next hop's start, a chain. A bridge reaching another, of the
                        //same thing or not, takes its line's flow away from where it was laid to go)
                        boolean chainIn = fOut && found.net == q.net && i == 1 && found.x + Geo.dx(found.rot) == q.x && found.y + Geo.dy(found.rot) == q.y;
                        //(of whatever lines: a bridge reached takes nothing more from that side, DirectionBridge.occupied)
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
        return b.partAt[i] == null && !(solid && b.clear[i]) && !own[i] && b.floor(x, y) && (b.held == null || !b.held[i]);
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
                boolean holds = target.equals(p.config) || p.recipe != null && (p.recipe.in.containsKey(target) || p.recipe.out.containsKey(target) || p.recipe.perYield.containsKey(target) || p.recipe.fixedIn.containsKey(target));
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

    // ------------------------------------------------------------------ payloads

    /** Whether a payload conveyor can stand with its centre here: every tile of it free of bodies and its line, floor. */
    boolean openSquare(int cx, int cy, int s){
        int x0 = Geo.lo(cx, s), y0 = Geo.lo(cy, s);
        for(int y = y0; y < y0 + s; y++) for(int x = x0; x < x0 + s; x++) if(!open(x, y, blockOf(Kind.payload).solid)) return false;
        return true;
    }

    float squarePrice(int cx, int cy, int s){
        int x0 = Geo.lo(cx, s), y0 = Geo.lo(cy, s);
        float c = cost(Kind.payload);
        for(int y = y0; y < y0 + s; y++) for(int x = x0; x < x0 + s; x++){
            int i = b.idx(x, y);
            if(hard && !at[i].isEmpty()) return Float.POSITIVE_INFINITY;
            c += hist[i] / (s * s) + pf * at[i].size();
        }
        return c;
    }

    /**
     * A line of payload conveyors from a maker's front to a taker: each conveyor hands what it holds to what is ahead of
     * its centre (PayloadConveyor: next, aligned and of its size, or a block that takes payloads), and holds one; the
     * search's states are a conveyor's centre, the way it is entered and how many the line holds so far (to the
     * buffer it must have), each step a conveyor's width.
     */
    boolean layPayload(Net net, Body.Part sink){
        int s = blockOf(Kind.payload).size, need = net.buffer + 1;
        int n = b.w * b.h * 4 * (need + 1);
        float[] dist = new float[n];
        int[] from = new int[n];
        Arrays.fill(dist, Float.POSITIVE_INFINITY);
        PriorityQueue<long[]> open = new PriorityQueue<>((p, q) -> Double.compare(Double.longBitsToDouble(p[0]), Double.longBitsToDouble(q[0])));
        Body.Part m = net.from;
        int[] f = Board.front(m);
        int d0 = m.rot;
        float unit = cost(Kind.payload);
        for(int k = -1; k <= 1; k++){
            int cx = f[0] + Geo.dx(d0) * (s / 2) + Geo.dx((d0 + 1) % 4) * k, cy = f[1] + Geo.dy(d0) * (s / 2) + Geo.dy((d0 + 1) % 4) * k;
            if(!b.in(cx, cy) || !openSquare(cx, cy, s)) continue;
            int st = ((b.idx(cx, cy) * 4) + d0) * (need + 1);
            dist[st] = 0f;
            from[st] = -1;
            open.add(new long[]{Double.doubleToLongBits(gap(cx, cy, sink) / (float)s * unit), st});
        }
        int goal = -1, goalRot = -1;
        float best = Float.POSITIVE_INFINITY;
        while(!open.isEmpty()){
            long[] top = open.poll();
            int st = (int)top[1];
            if(Double.longBitsToDouble(top[0]) >= best) break;
            int held = st % (need + 1), d = st / (need + 1) % 4, idx = st / (need + 1) / 4;
            int cx = idx % b.w, cy = idx / b.w;
            for(int e = 0; e < 4; e++){
                if(e == (d + 2) % 4) continue;
                float c = squarePrice(cx, cy, s);
                if(c == Float.POSITIVE_INFINITY) continue;
                float nd = dist[st] + c;
                int h2 = Math.min(need, held + 1);
                //(ahead of its centre: the taker, which takes it from this side, with the line holding enough)
                int fx = cx + Geo.dx(e) * (s / 2 + 1), fy = cy + Geo.dy(e) * (s / 2 + 1);
                if(b.in(fx, fy) && sink.covers(fx, fy)){
                    //(a conveyor hands on only to a block of its size straight ahead, or a bigger one it lies wholly against,
                    //PayloadConveyor.onProximityUpdate)
                    int off = e % 2 == 0 ? Math.abs(sink.ay() - cy) : Math.abs(sink.ax() - cx);
                    boolean aligned = sink.size == s ? sink.ax() == cx + Geo.dx(e) * s && sink.ay() == cy + Geo.dy(e) * s : sink.size > s && off * 2 <= sink.size - s;
                    if(aligned && h2 >= need && Board.takesPayloadFrom(sink, cx + Geo.dx(e) * (s / 2), cy + Geo.dy(e) * (s / 2)) && nd < best){
                        best = nd;
                        goal = st;
                        goalRot = e;
                    }
                    continue;
                }
                int nx = cx + Geo.dx(e) * s, ny = cy + Geo.dy(e) * s;
                if(!b.in(nx, ny) || !openSquare(nx, ny, s) || squareOnPath(from, need, st, nx, ny, s)) continue;
                int ns = ((b.idx(nx, ny) * 4) + e) * (need + 1) + h2;
                if(nd < dist[ns]){
                    dist[ns] = nd;
                    from[ns] = st * 4 + e;
                    open.add(new long[]{Double.doubleToLongBits(nd + gap(nx, ny, sink) / (float)s * unit), ns});
                }
            }
        }
        if(goal < 0) return false;
        List<Piece> laid = new ArrayList<>();
        int st = goal, rot = goalRot;
        while(true){
            int idx = st / (need + 1) / 4;
            Piece q = piece(Kind.payload, idx % b.w, idx / b.w, rot, net.id, net.res);
            q.outs = 1 << rot;
            laid.add(0, q);
            if(from[st] < 0) break;
            rot = from[st] % 4;
            st = from[st] / 4;
        }
        for(Piece q : laid){
            net.pieces.add(q);
            place(q);
        }
        return true;
    }

    /** Whether a square about (x, y) overlaps a conveyor of the path to a state. */
    boolean squareOnPath(int[] from, int need, int st, int x, int y, int s){
        int cur = st;
        while(cur >= 0){
            int idx = cur / (need + 1) / 4;
            if(Math.abs(idx % b.w - x) < s && Math.abs(idx / b.w - y) < s) return true;
            cur = from[cur] < 0 ? -1 : from[cur] / 4;
        }
        return false;
    }

    Piece goalJoin;
    final Map<Integer, Net> byId = new HashMap<>();

    /**
     * A piece on a tile of another line, taking from this piece, that carries the same to the same taker (one thing, or
     * items to the core alone): of a line laid that reaches the taker, itself or through lines it joined in turn (a tree
     * of lines gathering into one taker), every piece joined on the way still standing; or of a line that stands. What
     * then runs on its end, all the lines gathered, within what one of its carriers carries; into a line that stands,
     * within what it carries at most, less what it carries now.
     */
    Piece joinAt(Net net, Body.Part sink, int x, int y, Piece last){
        if(!b.in(x, y) || net.cls == 1) return null;
        int dir = Geo.dir(x, y, last.x, last.y);
        if(dir < 0) return null;
        for(Piece o : at[b.idx(x, y)]){
            if(o.net == net.id || o.block == null || !family(o, last) || !Board.pieceTakes(o, dir, last, null)) continue;
            if(o.line != null){
                //(a line that stands, to this taker alone: what is handed it goes nowhere else)
                if(!(coreBound(net) && o.line.coreBound() || o.res.equals(net.res) && o.line.onlyTo(sink))) continue;
                if(o.line.load + into(o.line, sink) + flowTo(net, sink) > o.line.cap + 1e-4f) continue;
                return o;
            }
            if(o.fixed) continue;
            Net other = byId.get(o.net);
            if(other == null || !other.to.contains(sink) || !(o.res.equals(net.res) || coreBound(net) && coreBound(other))) continue;
            Net root = root(other, sink, net);
            if(root == null) continue;
            if(gathered(root, sink) + flowTo(net, sink) > carries(root) + 1e-4f) continue;
            Standing end = standingEnd(root, sink);
            if(end != null && end.load + into(end, sink) + flowTo(net, sink) > end.cap + 1e-4f) continue;
            return o;
        }
        return null;
    }

    /**
     * The line laid that a line's way to a taker ends in, following what it joined: the one that reaches the taker, or runs
     * on into a line that stands (null: not reached, or through the line asked).
     */
    Net root(Net n, Body.Part sink, Net not){
        for(int k = 0; k <= nets.size(); k++){
            if(n == null || n == not || n.unreached.contains(sink)) return null;
            Piece j = n.joins.get(sink);
            if(j == null || j.line != null) return n;
            if(!at[b.idx(j.x, j.y)].contains(j)) return null;
            n = byId.get(j.net);
        }
        return null;
    }

    /** The line that stands a root runs on into, toward a taker (null: none). */
    static Standing standingEnd(Net root, Body.Part sink){
        Piece j = root.joins.get(sink);
        return j == null ? null : j.line;
    }

    /** What runs into a taker on the end of a line: its own and all the lines gathered into it (worked out once a lay). */
    float gathered(Net root, Body.Part sink){
        return gatheredOf.computeIfAbsent(root, r -> {
            float f = r.flow();
            for(Net n : nets) if(n != r && n.joins.containsKey(sink) && root(n, sink, null) == r) f += flowTo(n, sink);
            return f;
        });
    }

    /** What the lines laid bring into a line that stands, toward a taker (worked out once a lay). */
    float into(Standing s, Body.Part sink){
        return intoOf.computeIfAbsent(s, k -> {
            float f = 0f;
            for(Net n : nets){
                if(!n.joins.containsKey(sink)) continue;
                Net r = root(n, sink, null);
                if(r != null && standingEnd(r, sink) == s) f += flowTo(n, sink);
            }
            return f;
        });
    }

    final Map<Net, Float> gatheredOf = new HashMap<>();
    final Map<Standing, Float> intoOf = new HashMap<>();

    static float flowTo(Net n, Body.Part sink){
        int i = n.to.indexOf(sink);
        return i < 0 || i >= n.flows.size() ? 0f : n.flows.get(i);
    }

    /** What one carrier of a line's kind carries a second (as measured, Carriers). */
    float carries(Net n){
        if(n.cls == 1){
            Block sb = blockOf(Kind.surge);
            Float r = sb == null ? null : b.carriers.rate.get(sb);
            if(r != null) return r;
        }
        Block bl = blockOf(n.cls == 2 ? Kind.conduit : Kind.duct);
        Float r = bl == null ? null : b.carriers.rate.get(bl);
        return r == null ? Float.MAX_VALUE : r;
    }

    /** An empty tile a conduit may leak onto: no block, not solid ground, nothing of a line on it. */
    boolean leakable(int x, int y){
        int i = b.idx(x, y);
        return b.partAt[i] == null && !b.solidGround[i] && !own[i];
    }

    /** Lays the line to one taker (null: a drain's empty tile): true if laid. */
    /**
     * Lays the line to one taker (null: a drain's empty tile): true if laid. Sought first about the maker and the taker
     * (the square about them and the line so far, so many tiles more each way), then wider, at last the whole map: a
     * line seldom goes far out of its way, and a search of the whole map for each line, each round, is most of the time.
     */
    boolean lay(Net net, Body.Part sink){
        //(a taker no way could reach, however dear, is not sought: a search of the whole map to find nothing)
        if(sink != null && net.cls != 3 && !mayReach(net, sink)){
            walledOff.computeIfAbsent(net, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(sink);
            return false;
        }
        if(sink == null || b.w * b.h <= 64 * 64) return layIn(net, sink, null);
        int x0 = Math.min(net.from.x0, sink.x0), y0 = Math.min(net.from.y0, sink.y0), x1 = Math.max(net.from.x1(), sink.x1()), y1 = Math.max(net.from.y1(), sink.y1());
        for(Piece q : net.pieces){
            x0 = Math.min(x0, q.x);
            y0 = Math.min(y0, q.y);
            x1 = Math.max(x1, q.x);
            y1 = Math.max(y1, q.y);
        }
        for(int margin : new int[]{12, 36}){
            int[] box = {x0 - margin, y0 - margin, x1 + margin, y1 + margin};
            if(box[0] <= 0 && box[1] <= 0 && box[2] >= b.w - 1 && box[3] >= b.h - 1) break;
            if(layIn(net, sink, box)) return true;
        }
        return layIn(net, sink, null);
    }

    /** The square the search keeps within (null: the whole map). */
    int[] box;
    /** Whether each move is asked if it crosses the way that leads to it (else only the way found, at the end). */
    boolean strict;

    /** Whether a way found crosses itself: two of its pieces on a tile, or a bridge of its kind on the span of its own hop. */
    boolean crossesItself(Net net, List<Piece> laid){
        Set<Integer> tiles = new HashSet<>();
        for(Piece q : laid) for(int t : tilesOf(q)) if(!tiles.add(t)) return true;
        for(Piece q : net.pieces) for(int t : tilesOf(q)) if(tiles.contains(t)) return true;
        for(int i = 0; i + 1 < laid.size(); i++){
            Piece a = laid.get(i), c = laid.get(i + 1);
            if(!(a.kind == Kind.bridgeIn || a.kind == Kind.lbridgeIn)) continue;
            for(Piece q : laid){
                if(q == a || q == c || !bridgeFamily(q, a)) continue;
                boolean between = a.x == c.x ? q.x == a.x && (q.y - a.y) * (c.y - q.y) > 0 : q.y == a.y && (q.x - a.x) * (c.x - q.x) > 0;
                if(between) return true;
            }
        }
        return false;
    }

    boolean layIn(Net net, Body.Part sink, int[] within){
        box = within;
        try{
            return layBoxed(net, sink);
        }finally{
            box = null;
        }
    }

    boolean layBoxed(Net net, Body.Part sink){
        ensure();
        now++;
        gatheredOf.clear();
        intoOf.clear();
        int w = b.w;
        PriorityQueue<long[]> open = new PriorityQueue<>((p, q) -> Double.compare(Double.longBitsToDouble(p[0]), Double.longBitsToDouble(q[0])));
        float hmin = net.cls == 0 ? Math.min(cost(Kind.duct), (2f * cost(Kind.bridgeIn) + this.w.tile() * (range - 1)) / (range + 1)) : net.cls == 1 ? cost(Kind.surge) : Math.min(cost(Kind.conduit), (2f * cost(Kind.lbridgeIn) + this.w.tile() * (lrange - 1)) / (lrange + 1));
        //starts: the tiles about the maker it hands the thing to
        Body.Part from = net.from;
        int starts = 0;
        for(int[] t : from.around()){
            if(!open(t[0], t[1], false)) continue;
            List<String> gives = Board.out(from, t[2]);
            if(!gives.contains(net.res)) continue;
            push(open, state(b.idx(t[0], t[1]), t[2], 1), 0f, -1, 0, gap(t[0], t[1], sink) * hmin);
            starts++;
        }
        if(Boolean.getBoolean("bp.debugStarts") && starts == 0){
            StringBuilder why = new StringBuilder();
            for(int[] t : from.around()) why.append(String.format(" %d,%d/%d:%s%s%s", t[0], t[1], t[2], b.in(t[0], t[1]) ? (b.partAt[b.idx(t[0], t[1])] != null ? "part" : b.floor(t[0], t[1]) ? "floor" : "nofloor") : "out", own[b.in(t[0], t[1]) ? b.idx(t[0], t[1]) : 0] ? "+own" : "", Board.out(from, t[2])));
            System.out.println("  no start for " + net + ":" + why);
        }
        //and the line's ducts so far, turned to routers: their sides (ducts only)
        float bestGoal = Float.MAX_VALUE;
        int goalSt = -1, goalMv = -1, goalTap = -1;
        goalJoin = null;
        //(a line's ducts so far turned to routers, its conduits to liquid routers: their sides; where a router hands on
        //ahead to what takes from it, a straight run)
        //(a duct, straight or turning, becomes a router facing the way it is fed: it hands on ahead and to both sides,
        //the way the line went one of them; not one another line joined, which a router would take from its back only)
        Set<Piece> joined = Collections.newSetFromMap(new IdentityHashMap<>());
        for(Net o : nets) if(o != net) joined.addAll(o.joins.values());
        if(net.cls != 1 && sink != null) for(int pi = 0; pi < net.pieces.size(); pi++){
            Piece p = net.pieces.get(pi);
            Kind tapKind = net.cls == 0 ? Kind.router : Kind.lrouter;
            boolean router = p.kind == tapKind;
            boolean duct = net.cls == 0 && (p.kind == Kind.duct || p.kind == Kind.armored) && p.from >= 0 && !joined.contains(p);
            if(!router && !duct && !(net.cls == 2 && p.kind == Kind.conduit && p.from == p.rot && straightAfter(net, p))) continue;
            int rrot = duct ? p.from : p.rot;
            int[] ways = net.cls == 0 ? new int[]{rrot, (rrot + 1) % 4, (rrot + 3) % 4} : new int[]{(p.rot + 1) % 4, (p.rot + 3) % 4};
            for(int side : ways){
                if((p.outs & (1 << side)) != 0) continue;
                int nx = p.x + Geo.dx(side), ny = p.y + Geo.dy(side);
                Piece r = piece(tapKind, p.x, p.y, rrot, net.id, net.res);
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
                if(box != null && (nx[0] < box[0] || nx[1] < box[1] || nx[0] > box[2] || nx[1] > box[3])) continue;
                //(a drain ends in a conduit that leaks, Conduit.leaks: a bridge's end hands on without leaking, moveLiquidForward(false))
                boolean goal = b.in(nx[0], nx[1]) && (sink == null ? leakable(nx[0], nx[1]) && type == 4 && blockOf(Kind.conduit) instanceof mindustry.world.blocks.liquid.Conduit cd && cd.leaks : inPart(nx[0], nx[1], sink));
                //(a surge line of one tile loads nothing)
                if(type == 3 && st % 3 == 1 && goal) continue;
                if(type == 2 || type == 5){
                    int ux = x + Geo.dx(e) * j, uy = y + Geo.dy(e) * j;
                    if(!open(x, y, solid(type == 2 ? Kind.bridgeIn : Kind.lbridgeIn)) || !open(ux, uy, solid(type == 2 ? Kind.bridgeOut : Kind.lbridgeOut))) continue;
                    if(!clearOfBridges(x, y, e, 1, j - 1, type == 5)) continue;
                }
                //(or into another line carrying the same to the same taker, from a side it takes from: a merge; a line
                //that stands is joined so, its tiles no way for anything else)
                Piece join = null;
                List<Piece> ps = null;
                if(!goal && sink != null && type != 3 && b.in(nx[0], nx[1]) && !at[b.idx(nx[0], nx[1])].isEmpty()){
                    ps = pieces(net, st, m, false);
                    join = joinAt(net, sink, nx[0], nx[1], ps.get(ps.size() - 1));
                    if(join != null) goal = true;
                }
                if(!goal && !open(nx[0], nx[1], false)) continue;
                if(ps == null) ps = pieces(net, st, m, goal);
                //(not over the path itself: asked of every move only when the cheapest way found crossed itself, which a
                //way of least cost does not, a loop of it costing more than none)
                if(strict){
                    boolean crosses = false;
                    for(Piece q : ps) if(onPath(st, b.idx(q.x, q.y))) crosses = true;
                    if(!goal && onPath(st, b.idx(nx[0], nx[1]))) crosses = true;
                    if(crosses) continue;
                }
                //(first what it costs without its leaks, a bound below: most moves end there, a better way to where they
                //lead being known already)
                float low = 0f;
                for(Piece q : ps){
                    int qi = b.idx(q.x, q.y);
                    low += cost(q.kind) + hist[qi] + pf * at[qi].size();
                }
                if(type == 2 || type == 5) low += this.w.tile() * (j - 1);
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
                //(a bridge's span is ground spent too: what it passes over is left in scraps no machine fits, and no other
                //bridge of its kind may stand in line with it)
                if(type == 2 || type == 5) c += this.w.tile() * (j - 1);
                float ng = g[st] + c;
                if(goal && sink == null) ng += harmAt(net.res, nx[0], nx[1]);
                if(goal){
                    if(ng < bestGoal){
                        bestGoal = ng;
                        goalSt = st;
                        goalMv = m;
                        goalTap = -1;
                        goalJoin = join;
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
        if(!strict && crossesItself(net, laid)){
            strict = true;
            try{
                return layBoxed(net, sink);
            }finally{
                strict = false;
            }
        }
        if(tapRef >= 0){
            Piece p = net.pieces.get(tapRef / 4);
            unplace(p);
            if(net.cls == 0 && p.kind != Kind.router) p.rot = p.from;
            p.kind = net.cls == 0 ? Kind.router : Kind.lrouter;
            p.block = blockOf(p.kind);
            p.outs |= 1 << (tapRef % 4);
            place(p);
        }
        for(Piece q : laid){
            net.pieces.add(q);
            place(q);
        }
        if(goalJoin != null && goalSt >= 0) net.joins.put(sink, goalJoin);
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

    /** The tiles a piece stands on (one, or a payload conveyor's square about its centre). */
    int[] tilesOf(Piece q){
        if(q.block == null || q.block.size == 1) return new int[]{b.idx(q.x, q.y)};
        int s = q.block.size, x0 = Geo.lo(q.x, s), y0 = Geo.lo(q.y, s);
        int[] out = new int[s * s];
        int k = 0;
        for(int y = y0; y < y0 + s; y++) for(int x = x0; x < x0 + s; x++) out[k++] = b.in(x, y) ? b.idx(x, y) : -1;
        return out;
    }

    void place(Piece q){
        if(q.block != null && q.block.size > 1){
            for(int t : tilesOf(q)) if(t >= 0){
                at[t].add(q);
                own[t] = true;
            }
            return;
        }
        int i = b.idx(q.x, q.y);
        at[i].add(q);
        own[i] = true;
        if(q.kind == Kind.bridgeIn || q.kind == Kind.bridgeOut) itemBridges[i]++;
        if(q.kind == Kind.lbridgeIn || q.kind == Kind.lbridgeOut) liquidBridges[i]++;
    }

    void unplace(Piece q){
        if(q.block != null && q.block.size > 1){
            for(int t : tilesOf(q)) if(t >= 0) at[t].remove(q);
            return;
        }
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

    /** Joins whose piece is gone (the line joined laid again elsewhere): their takers are not reached, and are marked so. */
    int brokenJoins(Net net){
        int n = 0;
        for(var e : new ArrayList<>(net.joins.entrySet())){
            Piece o = e.getValue();
            if(!at[b.idx(o.x, o.y)].contains(o)){
                net.unreached.add(e.getKey());
                net.joins.remove(e.getKey());
                n++;
            }
        }
        return n;
    }

    /** How many rounds with nothing better before the laying stops: the more lines fight, the longer they may take to part. */
    int patience(){
        return 10 + nets.size() / 4;
    }

    /**
     * Whether a line could reach a taker at all, every way the search has allowed and more (a necessary condition, worked
     * out once a line and taker): over the tiles a piece may stand on, a step or a bridge's hop (of its kind, any length it
     * spans, over anything), from the tiles about the maker it hands the thing to, to a tile against the taker or a piece
     * of a line that goes to it. Whether one way of it can be laid is the search's to find.
     */
    boolean mayReach(Net net, Body.Part sink){
        Map<Body.Part, Boolean> of = reachable.computeIfAbsent(net, k -> new IdentityHashMap<>());
        Boolean known = of.get(sink);
        if(known != null) return known;
        boolean got = flood(net, sink);
        of.put(sink, got);
        return got;
    }

    final Map<Net, Map<Body.Part, Boolean>> reachable = new IdentityHashMap<>();

    boolean flood(Net net, Body.Part sink){
        int n = b.w * b.h;
        boolean[] seen = new boolean[n];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        int hop = net.cls == 2 ? lrange : range;
        for(int[] t : net.from.around()){
            if(!b.in(t[0], t[1])) continue;
            if(sink.covers(t[0], t[1])) return true;
            int i = b.idx(t[0], t[1]);
            if(!passable(i) || !Board.out(net.from, t[2]).contains(net.res)) continue;
            if(!seen[i]){
                seen[i] = true;
                q.add(i);
            }
        }
        while(!q.isEmpty()){
            int i = q.poll(), x = i % b.w, y = i / b.w;
            for(int e = 0; e < 4; e++){
                for(int j = 1; j <= Math.max(1, hop); j++){
                    int nx = x + Geo.dx(e) * j, ny = y + Geo.dy(e) * j;
                    if(!b.in(nx, ny)) break;
                    if(sink.covers(nx, ny) && j == 1) return true;
                    int ni = b.idx(nx, ny);
                    //(a piece of a line that goes to the taker: it may be joined)
                    if(j == 1) for(Piece o : at[ni]) if(o.net != net.id && o.block != null && (o.line != null ? o.line.to.contains(sink) : byId.containsKey(o.net) && byId.get(o.net).to.contains(sink))) return true;
                    //(a step to the next tile, or a hop to a tile further on: then on from there)
                    if(j == 1 || j >= 2 && hop >= 2){
                        if(!passable(ni) || seen[ni]) continue;
                        seen[ni] = true;
                        q.add(ni);
                    }
                }
            }
        }
        return false;
    }

    /** A tile a piece of a line may stand on (other lines' pieces there or not): no body's part, ground, not held whole. */
    boolean passable(int i){
        int x = i % b.w, y = i / b.w;
        return b.partAt[i] == null && b.floor(x, y) && (b.held == null || !b.held[i]);
    }

    /** Takers a line cannot reach at all (no way, whatever is shared): not sought again. */
    final Map<Net, Set<Body.Part>> walledOff = new IdentityHashMap<>();

    public boolean run(int maxRounds){
        own = new boolean[b.w * b.h];
        for(Net n : nets) byId.put(n.id, n);
        int bestBad = Integer.MAX_VALUE, since = 0;
        for(rounds = 1; rounds <= maxRounds; rounds++){
            for(Net net : nets){
                //(after the first round only the lines in what is still fought over, or not whole, are laid again: the
                //rest stand as they are)
                if(rounds > 1 && !involved(net)) continue;
                ripUp(net);
                Arrays.fill(own, false);
                net.unreached.clear();
                net.joins.clear();
                //(takers nearest the maker first; one the maker touches and hands to needs no line)
                List<Body.Part> order = new ArrayList<>(net.to);
                order.sort(Comparator.comparingInt(p -> gap(p, net.from)));
                for(Body.Part sink : order){
                    if(touches(net.from, sink, net.res, net.cls == 2)) continue;
                    //(a taker no way reaches, other lines' tiles allowed, no way reaches in any round: what stands in the
                    //way does not move while lines are laid)
                    if(walledOff.getOrDefault(net, Set.of()).contains(sink)){
                        net.unreached.add(sink);
                        continue;
                    }
                    if(!(net.cls == 3 ? layPayload(net, sink) : lay(net, sink))){
                        net.unreached.add(sink);
                        if(net.cls != 3 && net.pieces.isEmpty()) walledOff.computeIfAbsent(net, k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(sink);
                    }
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
            for(Net net : nets) unreached += net.unreached.size() + (net.drain && !net.drained ? 1 : 0) + brokenJoins(net);
            fought.clear();
            fought.addAll(bad);
            if(bad.isEmpty() && unreached == 0) return true;
            int nowBad = bad.size() + unreached;
            if(nowBad < bestBad){
                bestBad = nowBad;
                since = rounds;
            }
            boolean stop = rounds - since >= patience();
            if(Boolean.getBoolean("bp.debug") && (rounds == maxRounds || stop)) for(int i = 0; i < at.length; i++) if(at[i].size() > 1){
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
            if(Boolean.getBoolean("bp.debug") && (rounds == maxRounds || stop)) for(Net net : nets) for(Piece q : net.pieces){
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
            //(no better for a while: what is left is the placing's to mend)
            if(stop) break;
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
        for(Net net : nets) unreached += net.unreached.size() + (net.drain && !net.drained ? 1 : 0) + brokenJoins(net);
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
                //(a line with a taker no way reaches is never whole: laid again, it is only put back)
                boolean hopeless = false;
                for(Body.Part sink : net.to) if(walledOff.getOrDefault(net, Set.of()).contains(sink)) hopeless = true;
                if(hopeless) continue;
                List<Piece> old = new ArrayList<>(net.pieces);
                Set<Body.Part> oldUnreached = new LinkedHashSet<>(net.unreached);
                boolean oldDrained = net.drained;
                ripUp(net);
                Arrays.fill(own, false);
                net.unreached.clear();
                net.joins.clear();
                hard = true;
                boolean whole = true;
                List<Body.Part> order = new ArrayList<>(net.to);
                order.sort(Comparator.comparingInt(p -> gap(p, net.from)));
                for(Body.Part sink : order){
                    if(touches(net.from, sink, net.res, net.cls == 2)) continue;
                    if(!(net.cls == 3 ? layPayload(net, sink) : lay(net, sink))){
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

    /** Whether a maker hands this straight to a taker it touches (a payload out of its front only, into what takes it there). */
    static boolean touches(Body.Part from, Body.Part to, String res, boolean liquid){
        if(Board.isPayload(res)){
            int[] f = Board.front(from);
            return to.covers(f[0], f[1]) && Board.takesPayloadFrom(to, Board.front(from)[0] - Geo.dx(from.rot), Board.front(from)[1] - Geo.dy(from.rot));
        }
        for(int[] t : from.around()){
            if(!to.covers(t[0], t[1])) continue;
            List<String> gives = Board.out(from, t[2]);
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
