package expansion.econ;

import arc.math.geom.*;
import arc.struct.*;
import expansion.Blueprint.Part;
import mindustry.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;

import java.util.*;

/**
 * Beam nodes that put every powered machine of the plan on one grid with the generators: a Steiner tree grown the way
 * Takahashi and Matsuyama grow one, each machine in the order it is built joined to the tree as it stands by the fewest
 * nodes (Dijkstra over the tiles a node could stand on, a step being a node another one's beam reaches).
 *
 * The game's rule (BeamNode.updateDirections): a beam node links along each of its four axes to the first building with
 * power within its range; buildings without power and the rock are no hindrance. So whether a beam reaches something is
 * judged against the plan when it is all built: a beam that passes a machine built later would stop there then. That
 * machine is then linked to both ends instead, so a tree of nodes is never cut by what goes up after it; but a machine
 * reached past such a place would be cut off, and is not counted as reached. Two buildings that touch are on one grid
 * unless both only use power.
 */
final class Wiring{
    final Grid grid;
    final Block node;
    final int range;
    /** Origins (as grid positions) of everything on the tree, and the tiles a node of the tree beams across. */
    final IntSet tree = new IntSet(), lit = new IntSet();
    final StringBuilder log;

    Wiring(Grid grid, StringBuilder log){
        this.grid = grid;
        this.log = log;
        node = Vars.content.block("beam-node");
        range = node instanceof BeamNode b ? b.range : 10;
        //what of the side's has power from a generator already: the biggest grid is the tree to begin with; the others
        //are islands, to be joined to it
        Map<Object, IntSeq> graphs = new LinkedHashMap<>();
        Map<Object, Float> made = new HashMap<>();
        for(Building b : grid.team.data().buildings){
            if(b.power == null || b.tile != Vars.world.tile(b.tileX(), b.tileY())) continue;
            if(b.power.graph.producers.isEmpty()) continue;
            graphs.computeIfAbsent(b.power.graph, g -> new IntSeq()).add(grid.pos(b.tileX(), b.tileY()));
            if(b.block instanceof PowerGenerator gen) made.merge(b.power.graph, gen.powerProduction, Float::sum);
        }
        Object biggest = null;
        for(Object g : graphs.keySet()) if(biggest == null || made.getOrDefault(g, 0f) > made.getOrDefault(biggest, 0f)) biggest = g;
        for(var e : graphs.entrySet()){
            if(e.getKey() == biggest) for(int i = 0; i < e.getValue().size; i++) add(e.getValue().get(i));
            else islands.add(e.getValue());
        }
    }

    /** Grids of the map's own, with generators, that are not on the tree. */
    final List<IntSeq> islands = new ArrayList<>();

    /** Joins every island to the tree: the nodes it took. */
    /** The map's grids that could not be joined to the rest: what they make and use is no part of the plan's grid. */
    final List<IntSeq> unjoined = new ArrayList<>();

    Seq<Part> joinIslands(){
        Seq<Part> out = new Seq<>();
        for(IntSeq island : islands){
            Seq<Part> best = null;
            //from the island's generator nearest the tree
            for(int i = 0; i < island.size; i++){
                int o = island.get(i);
                Block b = grid.block(o % grid.w, o / grid.w);
                if(!(b instanceof PowerGenerator)) continue;
                Seq<Part> nodes = connect(new Part(b, o % grid.w, o / grid.w, 0, null));
                if(nodes != null){
                    best = nodes;
                    break;
                }
            }
            if(best == null){
                unjoined.add(island);
                log.append("a grid of the map's own could not be joined to the rest\n");
                continue;
            }
            out.addAll(best);
            for(int i = 0; i < island.size; i++) add(island.get(i));
        }
        relight();
        return out;
    }

    /** The origin of the powered building on a tile (planned before standing), or -1. */
    int powered(int x, int y){
        if(!grid.in(x, y)) return -1;
        Block b = grid.block(x, y);
        if(b == null || !b.hasPower || !b.connectedPower || b instanceof PowerNode) return -1;
        return grid.pos(grid.originX(x, y), grid.originY(x, y));
    }

    boolean insulated(int x, int y){
        Block b = grid.block(x, y);
        return b != null && b.insulated;
    }

    boolean isNode(int origin){
        return grid.block(origin % grid.w, origin / grid.w) instanceof BeamNode;
    }

    /** Puts something on the tree; a node's beams light the tiles up to the first powered thing, where a new node would be linked. */
    void add(int origin){
        tree.add(origin);
        if(!isNode(origin)) return;
        int x = origin % grid.w, y = origin / grid.w;
        for(int d = 0; d < 4; d++){
            for(int j = 1; j <= range; j++){
                int tx = x + Geometry.d4x(d) * j, ty = y + Geometry.d4y(d) * j;
                if(!grid.in(tx, ty) || insulated(tx, ty) || powered(tx, ty) >= 0) break;
                lit.add(grid.pos(tx, ty));
            }
        }
    }

    boolean pureConsumer(Block b){
        return b.consumesPower && !b.outputsPower;
    }

    /** Whether a machine as planned is on the tree already: touching it, or the first thing a beam of it reaches. */
    boolean reached(Part p){
        Block b = p.block;
        for(int[] t : Grid.around(p)){
            int o = powered(t[0], t[1]);
            if(o >= 0 && tree.contains(o)){
                Block ob = grid.block(t[0], t[1]);
                if(!(pureConsumer(b) && pureConsumer(ob))) return true;
            }
            //out from this edge along its side: the first powered thing, if a node of the tree within its range
            for(int j = 1; j <= range; j++){
                int tx = t[0] + Geometry.d4x(t[2]) * (j - 1), ty = t[1] + Geometry.d4y(t[2]) * (j - 1);
                if(!grid.in(tx, ty) || insulated(tx, ty)) break;
                int h = powered(tx, ty);
                if(h < 0) continue;
                if(tree.contains(h) && isNode(h)) return true;
                break;
            }
        }
        return false;
    }

    /**
     * For a side as it stands: the beam nodes that put a building no generator reaches back on the biggest grid, worked out
     * on the map as it is now (a beam taken by a machine put up later in its way, a node lost to a raid); null if there is
     * no way.
     */
    static Seq<Part> mend(Catalog cat, mindustry.game.Team team, mindustry.gen.Building cut){
        Wiring w = new Wiring(new Grid(cat, team), new StringBuilder());
        if(w.tree.isEmpty()) return null;
        //(from the whole of the grid it stands on: a node of that grid beside it is where a beam to the tree can start,
        //and it stands in the way of a new one from the machine itself)
        Seq<Part> from = new Seq<>();
        if(cut.power != null) for(mindustry.gen.Building b : cut.power.graph.all){
            if(b == cut || b.tile != Vars.world.tile(b.tileX(), b.tileY())) continue;
            from.add(new Part(b.block, b.tileX(), b.tileY(), b.rotation, null));
        }
        return w.connect(new Part(cut.block, cut.tileX(), cut.tileY(), cut.rotation, null), from);
    }

    /** Joins a machine to the tree with the fewest new nodes; the nodes, or null if there is no way. */
    Seq<Part> connect(Part p){
        return connect(p, new Seq<>());
    }

    /** The same, starting from the machine or from any of what stands on one grid with it (its island). */
    Seq<Part> connect(Part p, Seq<Part> island){
        int self = grid.pos(p.x, p.y);
        Seq<Part> out = new Seq<>();
        if(tree.isEmpty() || reached(p)){
            add(self);
            return out;
        }
        int N = grid.w * grid.h;
        IntFloatMap dist = new IntFloatMap();
        IntIntMap parent = new IntIntMap();
        PriorityQueue<float[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        //a first node: in line with an edge of the machine (or of a member of its island), nothing powered between
        Seq<Part> starts = new Seq<>();
        starts.add(p);
        starts.addAll(island);
        for(Part start : starts) for(int[] t : Grid.around(start)){
            for(int j = 1; j <= range; j++){
                int tx = t[0] + Geometry.d4x(t[2]) * (j - 1), ty = t[1] + Geometry.d4y(t[2]) * (j - 1);
                if(!grid.in(tx, ty) || insulated(tx, ty) || powered(tx, ty) >= 0) break;
                int q = grid.pos(tx, ty);
                if(!placeable(q)) continue;
                float c = 1f + j * 0.01f + grid.kept[q] * 0.1f;
                if(c < dist.get(q, Float.MAX_VALUE)){
                    dist.put(q, c);
                    parent.put(q, -1);
                    open.add(new float[]{c, q});
                }
            }
        }
        int done = -1, expanded = 0;
        while(!open.isEmpty() && expanded++ < 200000){
            float[] top = open.poll();
            int q = (int)top[1];
            if(top[0] > dist.get(q, Float.MAX_VALUE) + 1e-4f) continue;
            if(lit.contains(q)){
                done = q;
                break;
            }
            int x = q % grid.w, y = q / grid.w;
            boolean hit = false;
            for(int d = 0; d < 4 && !hit; d++){
                for(int j = 1; j <= range; j++){
                    int tx = x + Geometry.d4x(d) * j, ty = y + Geometry.d4y(d) * j;
                    if(!grid.in(tx, ty) || insulated(tx, ty)) break;
                    int h = powered(tx, ty);
                    if(h >= 0){
                        if(tree.contains(h)) hit = true;
                        break;
                    }
                    int r = grid.pos(tx, ty);
                    if(!placeable(r)) continue;
                    float c = top[0] + 1f + j * 0.01f + grid.kept[r] * 0.1f;
                    if(c < dist.get(r, Float.MAX_VALUE)){
                        dist.put(r, c);
                        parent.put(r, q);
                        open.add(new float[]{c, r});
                    }
                }
            }
            if(hit){
                done = q;
                break;
            }
        }
        if(done < 0) return null;
        for(int q = done; q >= 0; q = parent.get(q, -1)){
            Part n = new Part(node, q % grid.w, q / grid.w, 0, null);
            out.add(n);
            grid.plan(n);
        }
        //the nodes go up from the machine's end outwards; each joins the tree
        out.reverse();
        add(self);
        for(Part n : out) add(grid.pos(n.x, n.y));
        //lit tiles are worked out again: the new nodes cut the beams they stand across
        relight();
        return out;
    }

    boolean placeable(int q){
        int x = q % grid.w, y = q / grid.w;
        return grid.free(x, y) && !grid.noSolid[q];
    }

    void relight(){
        lit.clear();
        IntSeq all = new IntSeq();
        for(var it = tree.iterator(); it.hasNext;) all.add(it.next());
        tree.clear();
        for(int i = 0; i < all.size; i++) add(all.get(i));
    }

    /** Powered things of the plan the generators do not reach once everything is up, by origin. */
    IntSet unreached(Collection<Part> machines){
        //every powered thing planned or standing, and who links to whom
        IntSet seen = new IntSet();
        IntQueue queue = new IntQueue();
        IntSet all = new IntSet();
        for(Part m : machines) all.add(grid.pos(m.x, m.y));
        for(Building b : grid.team.data().buildings) if(b.power != null && b.block.outputsPower && !b.block.consumesPower) queue.addLast(grid.pos(b.tileX(), b.tileY()));
        for(Part m : machines) if(m.block.outputsPower && !m.block.consumesPower) queue.addLast(grid.pos(m.x, m.y));
        while(!queue.isEmpty()){
            int o = queue.removeFirst();
            if(!seen.add(o)) continue;
            int x = o % grid.w, y = o / grid.w;
            Block b = grid.block(x, y);
            if(b == null) continue;
            for(int[] t : Grid.around(b.size, x, y)){
                int h = powered(t[0], t[1]);
                if(h >= 0 && !(pureConsumer(b) && pureConsumer(grid.block(t[0], t[1])))) queue.addLast(h);
            }
            if(b instanceof BeamNode){
                for(int d = 0; d < 4; d++){
                    for(int j = 1; j <= range; j++){
                        int tx = x + Geometry.d4x(d) * j, ty = y + Geometry.d4y(d) * j;
                        if(!grid.in(tx, ty) || insulated(tx, ty)) break;
                        int h = powered(tx, ty);
                        if(h >= 0){ queue.addLast(h); break; }
                    }
                }
            }else{
                //a node's beam that reaches this links both ways
                for(int[] t : Grid.around(b.size, x, y)){
                    for(int j = 1; j <= range; j++){
                        int tx = t[0] + Geometry.d4x(t[2]) * (j - 1), ty = t[1] + Geometry.d4y(t[2]) * (j - 1);
                        if(!grid.in(tx, ty) || insulated(tx, ty)) break;
                        int h = powered(tx, ty);
                        if(h < 0) continue;
                        if(isNode(h)) queue.addLast(h);
                        break;
                    }
                }
            }
        }
        IntSet out = new IntSet();
        for(var it = all.iterator(); it.hasNext;){
            int o = it.next();
            if(!seen.contains(o)) out.add(o);
        }
        return out;
    }
}
