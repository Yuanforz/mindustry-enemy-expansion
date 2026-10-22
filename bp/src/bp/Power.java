package bp;

import bp.Board.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.sandbox.*;

import java.util.*;

/**
 * Power to every block that uses it: the blocks with power are a graph (two touching are joined unless both only use
 * power, Building.getPowerConnections; a beam node joins the first block with power along each axis within its range,
 * not a power node, BeamNode.updateDirections), and beam nodes are added on free tiles (not on a beam's way: a node is
 * solid) until every block that uses power is joined to one that makes it: a Steiner tree grown by shortest paths
 * (Takahashi and Matsuyama 1980), from what has power to the nearest part without, a node a step, over the tiles a node
 * would reach from a node. Where power may be had anywhere (a power source put down), a source is a way to start too.
 */
public final class Power{
    final Board b;
    final List<Body> bodies;
    final List<Piece> pieces;
    final boolean free;
    final Block node, source;
    final float nodeCost, sourceCost;
    public final List<Piece> added = new ArrayList<>();
    public final List<String> unpowered = new ArrayList<>();

    /** A block with power on the map: a part or a piece. */
    static final class V{
        final Block block;
        final int x0, y0, size;
        final boolean needs;
        V(Block block, int x0, int y0, boolean needs){
            this.block = block;
            this.x0 = x0;
            this.y0 = y0;
            this.size = block.size;
            this.needs = needs;
        }
    }

    final List<V> vs = new ArrayList<>();
    int[] vAt, any;
    int[] parent;

    public Power(Board b, List<Body> bodies, List<Piece> pieces, boolean free, Weights w){
        this.b = b;
        this.bodies = bodies;
        this.pieces = pieces;
        this.free = free;
        node = b.carriers.cheapest(Carriers.Role.node, w);
        source = b.carriers.cheapest(Carriers.Role.powerSource, w);
        nodeCost = (float)w.cost(node);
        sourceCost = source == null ? Float.MAX_VALUE : (float)w.cost(source);
    }

    static boolean powered(Block bl){
        return bl.hasPower && bl.connectedPower;
    }

    static boolean onlyUses(Block bl){
        return bl.consumesPower && !bl.outputsPower && !bl.conductivePower;
    }

    static boolean needs(Block bl){
        return bl.consPower != null && !bl.consPower.buffered && !bl.conductivePower;
    }

    static boolean makes(Block bl){
        return bl.outputsPower && !bl.consumesPower || bl instanceof PowerSource || bl instanceof PowerGenerator;
    }

    int find(int i){
        while(parent[i] != i) i = parent[i] = parent[parent[i]];
        return i;
    }

    void union(int a, int c){
        parent[find(a)] = find(c);
    }

    void build(){
        int n = b.w * b.h;
        vAt = new int[n];
        any = new int[n];
        Arrays.fill(vAt, -1);
        for(Body body : bodies) for(Body.Part p : body.parts){
            for(int y = p.y0; y <= p.y1(); y++) for(int x = p.x0; x <= p.x1(); x++) if(b.in(x, y)) any[b.idx(x, y)] = 1;
            if(!powered(p.block)) continue;
            add(new V(p.block, p.x0, p.y0, needs(p.block)));
        }
        for(Piece q : pieces){
            //(every tile it stands on: a payload conveyor stands on nine about its centre)
            int s = q.block.size, x0 = Geo.lo(q.x, s), y0 = Geo.lo(q.y, s);
            for(int y = y0; y < y0 + s; y++) for(int x = x0; x < x0 + s; x++) if(b.in(x, y)) any[b.idx(x, y)] = 1;
            if(powered(q.block)) add(new V(q.block, x0, y0, false));
        }
        for(Piece q : added){
            any[b.idx(q.x, q.y)] = 1;
            add(new V(q.block, q.x, q.y, false));
        }
        parent = new int[vs.size()];
        for(int i = 0; i < parent.length; i++) parent[i] = i;
        //touching
        for(int i = 0; i < vs.size(); i++){
            V v = vs.get(i);
            for(int y = v.y0 - 1; y <= v.y0 + v.size; y++) for(int x = v.x0 - 1; x <= v.x0 + v.size; x++){
                boolean edge = (x == v.x0 - 1 || x == v.x0 + v.size) != (y == v.y0 - 1 || y == v.y0 + v.size);
                if(!edge || !b.in(x, y)) continue;
                int j = vAt[b.idx(x, y)];
                if(j < 0 || j == i) continue;
                if(onlyUses(v.block) && onlyUses(vs.get(j).block)) continue;
                union(i, j);
            }
        }
        //beams of the nodes there are
        for(int i = 0; i < vs.size(); i++){
            V v = vs.get(i);
            if(!(v.block instanceof BeamNode)) continue;
            for(int d = 0; d < 4; d++){
                int hit = beam(v.x0 + (v.size - 1) / 2, v.y0 + (v.size - 1) / 2, v.block, d, null);
                if(hit >= 0) union(i, hit);
            }
        }
    }

    void add(V v){
        int id = vs.size();
        vs.add(v);
        for(int y = v.y0; y < v.y0 + v.size; y++) for(int x = v.x0; x < v.x0 + v.size; x++) if(b.in(x, y)) vAt[b.idx(x, y)] = id;
    }

    /** The first block with power a node at (x, y) reaches along a way (not a power node); free tiles passed are listed. */
    int beam(int x, int y, Block nodeBlock, int d, List<Integer> passed){
        int off = nodeBlock.size / 2, range = Carriers.range(nodeBlock);
        for(int j = 1 + off; j <= range + off; j++){
            int nx = x + Geo.dx(d) * j, ny = y + Geo.dy(d) * j;
            if(!b.in(nx, ny)) return -1;
            int i = b.idx(nx, ny);
            int v = vAt[i];
            if(v >= 0 && !(vs.get(v).block instanceof PowerNode)) return v;
            if(passed != null && any[i] == 0) passed.add(i);
        }
        return -1;
    }

    boolean freeTile(int i){
        int x = i % b.w, y = i / b.w;
        return any[i] == 0 && !b.clear[i] && b.floor(x, y) && b.canStand(node, x, y, 0);
    }

    /** What a node on a free tile would join: the blocks with power it touches (unless both only use power) or beams to. */
    Set<Integer> joins(int i, boolean asSource){
        Set<Integer> out = new HashSet<>();
        int x = i % b.w, y = i / b.w;
        for(int d = 0; d < 4; d++){
            int nx = x + Geo.dx(d), ny = y + Geo.dy(d);
            if(!b.in(nx, ny)) continue;
            int v = vAt[b.idx(nx, ny)];
            if(v >= 0) out.add(v);
        }
        if(!asSource) for(int d = 0; d < 4; d++){
            int hit = beam(x, y, node, d, null);
            if(hit >= 0) out.add(hit);
        }
        return out;
    }

    public Power run(){
        while(true){
            vs.clear();
            build();
            //what has power, what still wants it
            Set<Integer> rooted = new HashSet<>();
            for(int i = 0; i < vs.size(); i++) if(makes(vs.get(i).block)) rooted.add(find(i));
            Set<Integer> wanting = new HashSet<>();
            for(int i = 0; i < vs.size(); i++) if(vs.get(i).needs && !rooted.contains(find(i))) wanting.add(find(i));
            if(wanting.isEmpty()) break;
            if(!grow(rooted, wanting)){
                for(int i = 0; i < vs.size(); i++) if(vs.get(i).needs && !rooted.contains(find(i))) unpowered.add(vs.get(i).block.name + "@" + vs.get(i).x0 + "," + vs.get(i).y0);
                break;
            }
        }
        return this;
    }

    /** One shortest path of nodes from what has power (or a new source) to a part that wants it; false if none. */
    boolean grow(Set<Integer> rooted, Set<Integer> wanting){
        int n = b.w * b.h;
        float[] dist = new float[n];
        int[] from = new int[n];
        boolean[] src = new boolean[n];
        Arrays.fill(dist, Float.MAX_VALUE);
        PriorityQueue<float[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        for(int i = 0; i < n; i++){
            if(!freeTile(i)) continue;
            boolean touchesRoot = false;
            for(int v : joins(i, false)) if(rooted.contains(find(v))) touchesRoot = true;
            if(touchesRoot){
                dist[i] = nodeCost;
                from[i] = -1;
                open.add(new float[]{dist[i], i});
            }else if(free && source != null){
                dist[i] = sourceCost;
                from[i] = -1;
                src[i] = true;
                open.add(new float[]{dist[i], i});
            }
        }
        while(!open.isEmpty()){
            float[] top = open.poll();
            int i = (int)top[1];
            if(top[0] > dist[i]) continue;
            //(reaches one that wants power: put the path down)
            for(int v : joins(i, src[i])) if(wanting.contains(find(v))){
                int at = i;
                while(at >= 0){
                    Piece q = new Piece(src[at] ? Kind.power : Kind.node, src[at] ? source : node, at % b.w, at / b.w, 0, -1, "power");
                    added.add(q);
                    at = from[at];
                }
                return true;
            }
            //(on to free tiles a node here would reach: touching, or along its beams over what has no power)
            List<Integer> next = new ArrayList<>();
            int x = i % b.w, y = i / b.w;
            for(int d = 0; d < 4; d++){
                int nx = x + Geo.dx(d), ny = y + Geo.dy(d);
                if(b.in(nx, ny)) next.add(b.idx(nx, ny));
                if(!src[i]) beam(x, y, node, d, next);
            }
            for(int j : next){
                if(!freeTile(j)) continue;
                float nd = dist[i] + nodeCost;
                if(nd < dist[j]){
                    dist[j] = nd;
                    from[j] = i;
                    src[j] = false;
                    open.add(new float[]{nd, j});
                }
            }
        }
        return false;
    }
}
