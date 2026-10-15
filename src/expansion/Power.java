package expansion;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import expansion.Blueprint.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;

import static mindustry.Vars.*;

/**
 * Gets power to something that has been planned.
 *
 * There are two places to get it from: a grid the enemy already has that makes more than everything on
 * it could use at once, or a new generator on a free vent. Either is reached with a chain of beam nodes.
 * A beam node joins up with the first powered building along each of the four directions, within its
 * range, so the chain is a walk in straight steps from a node of the new thing to something that has
 * power to give.
 */
public class Power{
    /** What a step in a chain weighs against a new generator when choosing between ways. */
    static final float nodeCost = 8f;

    private final Ground ground;
    private final Terrain terrain;
    //filled in when something is planned: this is made before the game has made its blocks
    public Block node;
    /** Left null where the enemy may not build generators of its own. */
    public @Nullable Block generator;
    /** The longest chain of nodes tried. */
    public int hops = 24;
    /** When a search for a way has to stop. */
    public long deadline = Long.MAX_VALUE;
    /**
     * How far a grid may be loaded when there is no vent left to put a generator on: slow is better
     * than nothing at all.
     */
    public float starved = 3f;
    /**
     * Whether what is being powered is something it can't do without and has none of: a first source
     * of an item. That goes on a grid however loaded the grid is. Everything on it slows down a
     * little more, which is better than doing without the thing altogether.
     */
    public boolean essential;
    private float allowed = 1.2f;
    /**
     * How far a grid may be loaded beyond what it makes. Everything on a grid slows down together when
     * it is short, and not everything on it runs all the time, so a little too much is better than
     * leaving a vent's worth unused.
     */
    public float overload = 1.2f;

    private final ObjectFloatMap<PowerGraph> spare = new ObjectFloatMap<>();
    private final IntIntMap ventAt = new IntIntMap(), parent = new IntIntMap(), depth = new IntIntMap();
    private final java.util.PriorityQueue<Long> queue = new java.util.PriorityQueue<>();
    /** Where power might be had, for steering the search: x and y of each. */
    private final IntSeq goals = new IntSeq();
    private final IntSet own = new IntSet(), bad = new IntSet(), targets = new IntSet();

    public Power(Ground ground, Terrain terrain){
        this.ground = ground;
        this.terrain = terrain;
    }

    /** What a grid makes beyond what everything on it would use running flat out. */
    public float spare(PowerGraph graph){
        float left = spare.get(graph, Float.NaN);
        if(Float.isNaN(left)){
            left = 0f;
            for(Building producer : graph.producers) left += producer.getPowerProduction();
            for(Building consumer : graph.consumers){
                if(consumer.block.consPower != null && !consumer.block.consPower.buffered) left -= consumer.block.consPower.usage;
            }
            spare.put(graph, left);
        }
        return left;
    }

    /**
     * Works out what has to be built for a layout to have the power it uses.
     * @param needed power a tick
     * @param avoid tiles that have to stay clear, such as where the layout's lines will run
     * @return the nodes and generators to add, none if it has power as it stands, or null if there is no way
     */
    public @Nullable Seq<Part> link(Layout layout, float needed, IntSet avoid){
        return link(layout.parts, needed, avoid);
    }

    /**
     * Joins up something that has been planned with something else that has, by a chain of nodes.
     * @param to the powered parts to reach
     * @return the nodes to add, or null if there is no way
     */
    public @Nullable Seq<Part> join(Seq<Part> parts, Seq<Part> to, IntSet avoid){
        Seq<Part> starts = parts.select(part -> part.block instanceof BeamNode);
        if(starts.isEmpty()) return null;
        spare.clear();
        own.clear();
        targets.clear();
        for(Part part : parts){
            if(part.block.hasPower) world.tile(part.x, part.y).getLinkedTilesAs(part.block, tile -> own.add(tile.pos()));
        }
        for(Part part : to){
            if(part.block.hasPower) world.tile(part.x, part.y).getLinkedTilesAs(part.block, tile -> targets.add(tile.pos()));
        }
        Seq<Part> found = search(starts, Float.MAX_VALUE, avoid, false);
        targets.clear();
        return found;
    }

    public @Nullable Seq<Part> link(Seq<Part> parts, float needed, IntSet avoid){
        Seq<Part> extra = new Seq<>();
        if(needed <= 0f) return extra;
        spare.clear();
        bad.clear();
        targets.clear();

        Seq<Part> starts = parts.select(part -> part.block instanceof BeamNode);
        if(starts.isEmpty()) return null;
        own.clear();
        for(Part part : parts){
            if(part.block.hasPower) world.tile(part.x, part.y).getLinkedTilesAs(part.block, tile -> own.add(tile.pos()));
        }

        boolean done = false;
        float total = needed, made = 0f;
        allowed = overload;
        for(int round = 0; round < 4 && !done; round++){
            Seq<Part> found = search(starts, needed, avoid, true);
            if(found == null) break;
            //what was found counts as standing while the rest is looked for
            ground.plan(found);
            extra.addAll(found);
            Part gen = found.find(part -> part.block == generator);
            if(gen == null){
                done = true;
            }else{
                made += output(gen.x, gen.y);
                needed -= output(gen.x, gen.y);
                done = needed <= 0f;
                for(Part part : found){
                    if(part.block instanceof BeamNode) starts.add(part);
                    world.tile(part.x, part.y).getLinkedTilesAs(part.block, tile -> own.add(tile.pos()));
                }
            }
        }
        if(!done){
            //no vent left within reach for all it needs. what it could put up may carry it, slowly;
            //or else it shares a grid that is already loaded
            float most = essential ? Float.MAX_VALUE : starved;
            if(made > 0f && total <= made * most){
                done = true;
            }else{
                allowed = most;
                Seq<Part> found = search(starts, needed, avoid, false);
                allowed = overload;
                if(found != null){
                    ground.plan(found);
                    extra.addAll(found);
                    done = true;
                }
            }
        }
        ground.unplan(extra);
        return done ? extra : null;
    }

    /**
     * A place in the queue: first by how many nodes the whole chain through it would come to at the
     * least, then by how many it has taken to get there.
     */
    private long key(int steps, int x, int y, int range){
        int nearest = Integer.MAX_VALUE;
        for(int i = 0; i < goals.size; i += 2) nearest = Math.min(nearest, Math.abs(goals.get(i) - x) + Math.abs(goals.get(i + 1) - y));
        //a beam reaches a node's range and what it ends on is a few tiles across
        int more = Math.max(nearest - 3, 0) / range;
        return ((long)(steps + more) << 40) | ((long)steps << 32) | (Point2.pack(x, y) & 0xffffffffL);
    }

    /** Whether a grid can take on this much more without everything on it slowing down by much. */
    private boolean bears(PowerGraph graph, float needed){
        if(spare(graph) >= needed) return true;
        float made = 0f;
        for(Building producer : graph.producers) made += producer.getPowerProduction();
        return made > 0f && (made - spare(graph) + needed) <= made * allowed;
    }

    /** What a generator would make standing on a vent. */
    private float output(int x, int y){
        return generator instanceof ThermalGenerator thermal ? thermal.powerProduction * thermal.sumAttribute(thermal.attribute, x, y) : ((PowerGenerator)generator).powerProduction;
    }

    /** The cheapest chain from one of the starting nodes to a grid with enough to spare, or to a free vent, with the generator to put there. */
    private @Nullable Seq<Part> search(Seq<Part> starts, float needed, IntSet avoid, boolean generate){
        long prof = Prof.start();
        try{
            return find(starts, needed, avoid, generate);
        }finally{
            Prof.end("power", prof);
        }
    }

    private @Nullable Seq<Part> find(Seq<Part> starts, float needed, IntSet avoid, boolean generate){
        int range = ((BeamNode)node).range;

        ventAt.clear();
        if(generator != null && generate){
            IntSeq vents = terrain.vents(generator, ground.team);
            int reach = range * (hops + 1) + 3;
            Part first = starts.first();
            for(int i = 0; i < vents.size; i++){
                int vx = Point2.x(vents.get(i)), vy = Point2.y(vents.get(i));
                if(Math.abs(vx - first.x) > reach || Math.abs(vy - first.y) > reach || !ground.fits(generator, vx, vy, 0)) continue;
                boolean clear = true;
                int offset = -(generator.size - 1) / 2;
                for(int dx = 0; dx < generator.size && clear; dx++){
                    for(int dy = 0; dy < generator.size && clear; dy++) clear = !avoid.contains(Point2.pack(vx + dx + offset, vy + dy + offset));
                }
                if(!clear) continue;
                for(int dx = 0; dx < generator.size; dx++){
                    for(int dy = 0; dy < generator.size; dy++) ventAt.put(Point2.pack(vx + dx + offset, vy + dy + offset), vents.get(i));
                }
            }
        }

        //where there might be power to be had: the vents, what is to be reached, and the grids there are. the search heads for the nearest
        goals.clear();
        for(var entry : ventAt){
            if(entry.value == entry.key) goals.add(Point2.x(entry.key), Point2.y(entry.key));
        }
        IntSet.IntSetIterator wanted = targets.iterator();
        while(wanted.hasNext){
            int pos = wanted.next();
            goals.add(Point2.x(pos), Point2.y(pos));
        }
        if(targets.isEmpty()){
            IntSet cells = new IntSet();
            for(Building build : ground.team.data().buildings){
                if(!build.block.hasPower || !build.block.connectedPower || build.power == null || own.contains(build.pos())) continue;
                if(cells.add(Point2.pack(build.tileX() / 6, build.tileY() / 6)) && bears(build.power.graph, needed)) goals.add(build.tileX(), build.tileY());
            }
        }
        if(goals.isEmpty()) return null;

        parent.clear();
        depth.clear();
        queue.clear();
        for(Part start : starts){
            int pos = Point2.pack(start.x, start.y);
            parent.put(pos, -1);
            depth.put(pos, 0);
            queue.add(key(0, start.x, start.y, range));
        }

        float bestCost = Float.MAX_VALUE;
        int bestEnd = -1, bestVent = -1, popped = 0;

        while(!queue.isEmpty()){
            long next = queue.poll();
            int pos = (int)(next & 0xffffffffL), px = Point2.x(pos), py = Point2.y(pos), steps = depth.get(pos, 0);
            //nothing still waiting can come out cheaper than what has been found
            if((next >> 40) * nodeCost >= bestCost) break;
            //one that was got to a shorter way since it was put in the queue
            if(((next >> 32) & 0xff) != steps) continue;
            Clock.spend(1);
            if(++popped > 3000 || ((popped & 63) == 0 && Clock.now() > deadline)) break;

            for(int d = 0; d < 4; d++){
                for(int i = 1; i <= range; i++){
                    int x = px + Geometry.d4x(d) * i, y = py + Geometry.d4y(d) * i, at = Point2.pack(x, y);
                    Tile tile = world.tile(x, y);
                    if(tile == null) break;
                    if(tile.build != null && tile.build.isInsulated()) break;

                    if(ground.look(x, y) && ground.block.hasPower && ground.block.connectedPower){
                        //the beam ends here. if it is what was to be reached, or a grid with enough to spare, so does the search
                        boolean reached = targets.contains(at) || (!own.contains(at) && ground.planned(x, y) == null && tile.build != null && tile.build.power != null
                            && bears(tile.build.power.graph, needed));
                        if(reached && steps * nodeCost < bestCost){
                            bestCost = steps * nodeCost;
                            bestEnd = pos;
                            bestVent = -1;
                        }
                        break;
                    }

                    int vent = ventAt.get(at, -1);
                    if(vent != -1){
                        //a generator here would be the first thing this beam meets
                        float cost = steps * nodeCost + generator.requirements[0].amount;
                        if(cost < bestCost){
                            bestCost = cost;
                            bestEnd = pos;
                            bestVent = vent;
                        }
                        continue;
                    }

                    if(steps < hops && depth.get(at, Integer.MAX_VALUE) > steps + 1 && !avoid.contains(at) && !bad.contains(at) && ground.clear(node, x, y)){
                        parent.put(at, pos);
                        depth.put(at, steps + 1);
                        queue.add(key(steps + 1, x, y, range));
                    }
                }
            }
        }

        if(bestEnd == -1) return null;
        Seq<Part> out = new Seq<>();
        for(int pos = bestEnd; parent.get(pos, -1) != -1; pos = parent.get(pos, -1)){
            out.add(new Part(node, Point2.x(pos), Point2.y(pos), 0));
        }
        if(bestVent != -1) out.add(new Part(generator, Point2.x(bestVent), Point2.y(bestVent), 0));
        return out;
    }
}
