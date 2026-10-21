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

    @SuppressWarnings("unchecked")
    public Router(Board b, List<Net> nets, float perTile, float perItem){
        this.b = b;
        this.nets = nets;
        this.perTile = perTile;
        this.perItem = perItem;
        at = new List[b.w * b.h];
        for(int i = 0; i < at.length; i++) at[i] = new ArrayList<>(1);
        hist = new float[b.w * b.h];
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
    int leaks(Piece q, Piece partner){
        int c = 0;
        for(int dir = 0; dir < 4; dir++){
            int nx = q.x + Geo.dx(dir), ny = q.y + Geo.dy(dir);
            if(!b.in(nx, ny)) continue;
            int n = b.idx(nx, ny);
            Body.Part p = b.partAt[n];
            if(p != null){
                if(Board.pieceTakes(q, dir, null, p)){
                    List<String> given = q.itemCarrier() ? Board.itemsOut(p) : q.liquidCarrier() ? Board.liquidsOut(p, (dir + 2) % 4) : List.of();
                    for(String y : given) if(!y.equals(q.res)) c++;
                }
                if(has(Board.handsDirs(q), dir) && (q.outs & (1 << dir)) == 0 && q.itemCarrier() && Board.isCore(p) && !b.goals.contains(q.res)) c++;
            }
            for(Piece o : at[n]){
                if(o.net == q.net || !family(o, q)) continue;
                if(has(Board.handsDirs(o), (dir + 2) % 4) && Board.pieceTakes(q, dir, o, null) && !o.res.equals(q.res)) c++;
                if(has(Board.handsDirs(q), dir) && Board.pieceTakes(o, (dir + 2) % 4, q, null) && !o.res.equals(q.res)) c++;
                //(a surge line hands its stack on to whatever surge conveyor it points at, taking or not)
                if(o.kind == Kind.surge && q.kind == Kind.surge && (o.role != 2 && o.x + Geo.dx(o.rot) == q.x && o.y + Geo.dy(o.rot) == q.y || q.role != 2 && q.x + Geo.dx(q.rot) == o.x && q.y + Geo.dy(q.rot) == o.y)) c++;
            }
        }
        if(isBridge(q)){
            //(the first bridge of its kind along each way from it within range: one pointing at it reaches it, and this
            //one reaches the first ahead of it; that is meant only between the two ends of a hop, or from an end into
            //the next hop's start just ahead of it on its own line, a chain)
            for(int dir = 0; dir < 4; dir++){
                for(int i = 1; i <= Carriers.range(q.block); i++){
                    int nx = q.x + Geo.dx(dir) * i, ny = q.y + Geo.dy(dir) * i;
                    if(!b.in(nx, ny)) break;
                    Piece found = null;
                    for(Piece o : at[b.idx(nx, ny)]) if(o != q && bridgeFamily(o, q)) found = o;
                    if(found == null) continue;
                    if(found != partner){
                        boolean qIn = q.kind == Kind.bridgeIn || q.kind == Kind.lbridgeIn, fIn = found.kind == Kind.bridgeIn || found.kind == Kind.lbridgeIn;
                        boolean same = found.res.equals(q.res);
                        //(it reaches this one: of another thing, a mix; of the same, a merge, unless this one stands
                        //between it and the end of its own hop, which then gets nothing)
                        if(found.rot == (dir + 2) % 4 && (!same || fIn)) c++;
                        //(this one reaches it: the same, unless this is the start of a hop and it is not its end)
                        if(dir == q.rot && (!same || qIn)) c++;
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

    float price(Piece q, Piece partner){
        int i = b.idx(q.x, q.y);
        return cost(q.kind) + hist[i] + pf * (at[i].size() + leaks(q, partner));
    }

    int gap(int x, int y, Body.Part p){
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
            for(Piece o : at[b.idx(nx, ny)]) if(liquid ? o.kind == Kind.lbridgeIn || o.kind == Kind.lbridgeOut : o.kind == Kind.bridgeIn || o.kind == Kind.bridgeOut) return false;
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

    /** Lays the line to one taker: true if laid. */
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
        if(net.cls == 0) for(int pi = 0; pi < net.pieces.size(); pi++){
            Piece p = net.pieces.get(pi);
            boolean router = p.kind == Kind.router;
            if(!router && !((p.kind == Kind.duct || p.kind == Kind.armored) && p.from == p.rot)) continue;
            for(int side : new int[]{(p.rot + 1) % 4, (p.rot + 3) % 4}){
                if((p.outs & (1 << side)) != 0) continue;
                int nx = p.x + Geo.dx(side), ny = p.y + Geo.dy(side);
                Piece r = piece(Kind.router, p.x, p.y, p.rot, net.id, net.res);
                r.from = p.from;
                r.outs = p.outs | (1 << side);
                float tap = (router ? 0f : cost(Kind.router) - cost(p.kind)) + pf * leaks(r, null);
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
                boolean goal = b.in(nx[0], nx[1]) && inPart(nx[0], nx[1], sink);
                //(a surge line of one tile loads nothing)
                if(type == 3 && st % 3 == 1 && goal) continue;
                if(type == 2 || type == 5){
                    int ux = x + Geo.dx(e) * j, uy = y + Geo.dy(e) * j;
                    if(!open(x, y, solid(type == 2 ? Kind.bridgeIn : Kind.lbridgeIn)) || !open(ux, uy, solid(type == 2 ? Kind.bridgeOut : Kind.lbridgeOut))) continue;
                    if(!clearOfBridges(x, y, e, 1, j - 1, type == 5)) continue;
                }
                if(!goal && !open(nx[0], nx[1], false)) continue;
                List<Piece> ps = pieces(net, st, m, goal);
                float c = 0f;
                for(Piece q : ps) c += price(q, ps.size() == 2 ? ps.get(ps.size() - 1 - ps.indexOf(q)) : null);
                float ng = g[st] + c;
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
            p.kind = Kind.router;
            p.outs |= 1 << (tapRef % 4);
            place(p);
        }
        for(Piece q : laid){
            net.pieces.add(q);
            place(q);
        }
        return true;
    }

    void push(PriorityQueue<long[]> open, int st, float ng, int parent, int m, float h){
        if(stamp[st] == now && g[st] <= ng) return;
        stamp[st] = now;
        g[st] = ng;
        par[st] = parent;
        mv[st] = m;
        open.add(new long[]{Double.doubleToLongBits(ng + h), st, Double.doubleToLongBits(ng) == 0 ? 0 : 0});
    }

    void place(Piece q){
        at[b.idx(q.x, q.y)].add(q);
        own[b.idx(q.x, q.y)] = true;
    }

    void unplace(Piece q){
        at[b.idx(q.x, q.y)].remove(q);
    }

    void ripUp(Net net){
        for(Piece q : net.pieces) unplace(q);
        net.pieces.clear();
    }

    // ------------------------------------------------------------------ all lines

    /** Lays every line, rounds of rip-up and re-lay, until nothing is fought over or so many rounds: true if clean. */
    public boolean run(int maxRounds){
        own = new boolean[b.w * b.h];
        for(rounds = 1; rounds <= maxRounds; rounds++){
            for(Net net : nets){
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
            for(Net net : nets) unreached += net.unreached.size();
            if(bad.isEmpty() && unreached == 0) return true;
            for(int i : bad) hist[i] += 1f;
            pf *= 1.6f;
        }
        rounds = maxRounds;
        return false;
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

    public List<Piece> all(){
        List<Piece> out = new ArrayList<>();
        for(Net n : nets) out.addAll(n.pieces);
        return out;
    }
}
