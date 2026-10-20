package expansion.econ;

import arc.math.geom.*;
import arc.struct.*;
import expansion.*;
import expansion.Blueprint.Part;
import mindustry.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.production.*;

import java.util.*;

/**
 * Turns a planned order into blocks on the map: where each machine stands, the lines between them, and, when a machine's
 * turn comes, the machine with its lines and the node that powers it.
 *
 * Machines that work the ground stand on the places the plan chose. The others go in cells: a furnace with the factories
 * it feeds touching it (silicon goes straight in, with no line), a refabricator in front of the factory that hands it its
 * units, an electrolyzer with its hydrogen side against the refabricator.
 *
 * What each machine sends where is settled first, by who needs what: a crusher's sand goes to the furnace nearest to it
 * that still wants sand, and so on; what is left over goes to the core. What a machine wants that nothing new makes is
 * taken off a line the map's own drills already lead to the core. Then the cell is put where every one of those lines can
 * be laid: places near the core are tried in turn, each with all its lines worked out, until one has them all. The lines
 * are kept on the ground from then on, and built with whichever machine at their ends comes first in the order... except
 * those into a machine from something already standing, which come with that machine.
 */
public final class Works{
    public final Catalog cat;
    public final Field field;
    public final Team team;
    public final Ground ground = new Ground();
    public final Terrain terrain = new Terrain();
    public final Lines lines, pipes;
    public final Power power;
    /** Where each machine of the plan is to stand. */
    public final Map<Machine, Part> at = new HashMap<>();
    /** Lines worked out beforehand, by the machine whose turn lays them. */
    final Map<Machine, Seq<Part>> laid = new HashMap<>();
    public final StringBuilder log = new StringBuilder();
    /** What each running duct of the team carries, by tile, as followed from the drill that feeds it. */
    final IntIntMap carries = new IntIntMap();

    /** Some of a thing going from one machine to another, or to the core when {@link #to} is null. */
    static final class Flow{
        final int res;
        final Machine from, to;
        final float rate;

        Flow(int res, Machine from, Machine to, float rate){
            this.res = res;
            this.from = from;
            this.to = to;
            this.rate = rate;
        }
    }

    final List<Flow> flows = new ArrayList<>();
    /** What each machine is to get off a line that runs already, by res. */
    final Map<Machine, List<Integer>> taps = new HashMap<>();
    List<Machine> order = List.of();

    public Works(Catalog cat, Field field, Team team, Set<String> allowed){
        this.cat = cat;
        this.field = field;
        this.team = team;
        ground.team = team;
        lines = new Lines(ground);
        lines.duct = Blocks.duct;
        lines.bridge = allowed.contains("duct-bridge") ? Blocks.ductBridge : null;
        lines.router = allowed.contains("duct-router") ? Blocks.ductRouter : null;
        lines.underflow = allowed.contains("underflow-duct") ? Blocks.underflowDuct : null;
        //long lines are what an economy that reaches out needs: the default search budgets are for lines inside a base
        lines.budget = lines.shortBudget = 200000;
        pipes = new Lines(ground);
        pipes.liquid = true;
        pipes.duct = Blocks.reinforcedConduit;
        pipes.bridge = allowed.contains("reinforced-bridge-conduit") ? Blocks.reinforcedBridgeConduit : null;
        pipes.budget = pipes.shortBudget = 200000;
        power = new Power(ground, terrain);
        power.node = Blocks.beamNode;
        power.generator = null;
        //the plan has worked out that there is power enough: a grid is not to be turned down for being loaded
        power.overload = 100f;
        power.starved = 100f;
        power.essential = true;
    }

    static Block block(Machine m){
        return Vars.content.block(m.recipe.block);
    }

    // ---------------------------------------------------------------- before anything is built

    /** Settles where every machine of the order stands, what goes where, and the lines for it. */
    public void prepare(List<Machine> order){
        this.order = order;
        //machines on the ground stand where the plan put them; what drills reach across is kept clear of solid things
        for(Machine m : order){
            if(m.recipe.ground == Recipe.Ground.none) continue;
            Part p = new Part(block(m), m.x, m.y, m.rotation, null);
            at.put(m, p);
            ground.plan(p);
            if(block(m) instanceof BeamDrill drill) beams(drill, m);
        }
        //the map's own drills reach across the ground too
        for(Machine m : field.existing) if(block(m) instanceof BeamDrill drill) beams(drill, m);
        for(Machine m : order) if(m.recipe.ground != Recipe.Ground.none) reserveNode(null, m);
        trace();
        settleFlows(order);
        layBase(order);
    }

    void beams(BeamDrill drill, Machine m){
        Point2 p = new Point2();
        int dx = Geometry.d4x(m.rotation), dy = Geometry.d4y(m.rotation);
        for(int lane = 0; lane < drill.size; lane++){
            drill.nearbySide(m.x, m.y, m.rotation, lane, p);
            for(int i = 0; i < drill.range; i++){
                Tile t = Vars.world.tile(p.x + dx * i, p.y + dy * i);
                if(t == null || t.solid()) break;
                ground.beams.add(t.pos());
            }
        }
    }

    /** Follows the ducts out of each of the team's drills, to know what every running line carries. */
    void trace(){
        for(Building b : team.data().buildings){
            int res = -1;
            if(b instanceof BeamDrill.BeamDrillBuild d && d.lastItem != null) res = cat.id(d.lastItem.name);
            if(b instanceof Drill.DrillBuild d && d.dominantItem != null) res = cat.id(d.dominantItem.name);
            //and out of what the map's crafters make, when they make one thing
            if(b.block instanceof GenericCrafter gc && gc.outputItems != null && gc.outputItems.length == 1) res = cat.id(gc.outputItems[0].item.name);
            if(res < 0) continue;
            IntQueue queue = new IntQueue();
            for(Building near : b.proximity) if(near.block instanceof Duct && near.front() != b) queue.addLast(near.pos());
            while(!queue.isEmpty()){
                int pos = queue.removeFirst();
                if(carries.containsKey(pos)) continue;
                Building at = Vars.world.build(pos);
                if(at == null || at.team != team) continue;
                if(at.block instanceof Duct){
                    carries.put(pos, res);
                    Building front = at.front();
                    if(front != null) queue.addLast(front.pos());
                }else if(at.block instanceof DuctRouter){
                    carries.put(pos, res);
                    for(Building near : at.proximity) if(near.block instanceof Duct && near.front() != at) queue.addLast(near.pos());
                }
            }
        }
    }

    /**
     * Who sends what to whom. A machine with two lines out hands its output to them by turns, half each, whatever was
     * meant; so each maker sends everything it makes of a thing one way. A machine's wants are met by whole makers, the
     * nearest first (a little too much only waits at its door); makers left over send to the core if the core keeps the
     * thing, and else to whatever new machine uses it. What a machine still wants after that is taken off a line that runs.
     */
    void settleFlows(List<Machine> order){
        List<Machine> makers = new ArrayList<>(order);
        for(Machine m : field.existing) if(m.recipe.ground == Recipe.Ground.vent) makers.add(m);
        //makers on the ground that already send a thing somewhere, and what crafters have left to give
        Set<String> sent = new HashSet<>();
        Map<String, Float> spare = new HashMap<>();
        for(Machine c : order){
            for(int k = 0; k < cat.size(); k++){
                float need = c.recipe.in[k];
                if(need <= 0f || cat.get(k).kind == Res.Kind.unit) continue;
                List<Machine> from = new ArrayList<>();
                //(a crafter may serve several: it feeds the factories it touches and they take only what they have room for)
                //an item the core keeps goes there from drills: a machine that uses it takes it off that line once it stands.
                //(led straight to a user, it would never reach the core, and the user itself may cost it)
                boolean keeps = cat.get(k).storable;
                for(Machine p : makers) if(p != c && p.out(k) > 1e-4f && (p.recipe.ground == Recipe.Ground.none || (!keeps && !sent.contains(key(p, k))))) from.add(p);
                from.sort(Comparator.comparingDouble(p -> Math.hypot(p.x - c.x, p.y - c.y)));
                for(Machine p : from){
                    if(need <= 1e-4f) break;
                    float give = p.recipe.ground == Recipe.Ground.none ? Math.min(need, spare.getOrDefault(key(p, k), p.out(k))) : p.out(k);
                    if(give <= 1e-4f) continue;
                    need -= give;
                    if(p.recipe.ground == Recipe.Ground.none) spare.put(key(p, k), spare.getOrDefault(key(p, k), p.out(k)) - give);
                    else sent.add(key(p, k));
                    flows.add(new Flow(k, p, c, give));
                }
                if(need > 1e-4f){
                    if(cat.get(k).kind == Res.Kind.item) taps.computeIfAbsent(c, x -> new ArrayList<>()).add(k);
                    else log.append(String.format("%s short of %s %.1f/s with nothing to lead it from\n", c.recipe.name, cat.get(k).name, need));
                }
            }
        }
        for(Machine p : order){
            for(int k = 0; k < cat.size(); k++){
                if(p.out(k) <= 1e-3f || cat.get(k).kind == Res.Kind.unit || sent.contains(key(p, k))) continue;
                if(p.recipe.ground == Recipe.Ground.none && spare.getOrDefault(key(p, k), p.out(k)) <= 1e-3f) continue;
                if(cat.get(k).storable){
                    flows.add(new Flow(k, p, null, spare.getOrDefault(key(p, k), p.out(k))));
                    continue;
                }
                if(cat.get(k).kind != Res.Kind.item) continue;
                Machine user = null;
                for(Machine c : order) if(c.recipe.in[k] > 0f && (user == null || Math.hypot(c.x - p.x, c.y - p.y) < Math.hypot(user.x - p.x, user.y - p.y))) user = c;
                if(user != null) flows.add(new Flow(k, p, user, p.out(k)));
            }
        }
    }

    static String key(Machine m, int res){
        return System.identityHashCode(m) + "/" + res;
    }

    // ---------------------------------------------------------------- the cells, tried until their lines all fit

    /** What one try put down, so that it can be taken back. */
    final class Attempt{
        final Seq<Part> planned = new Seq<>();
        final IntSeq kept = new IntSeq();
        final Map<Machine, Seq<Part>> lines = new HashMap<>();
        final List<Machine> placed = new ArrayList<>();
        /** Tiles of lines worked out that carry a thing into a machine, which another line of it may join. */
        final Map<String, IntSet> into = new HashMap<>();
        /** Plain ducts of lines worked out to the core, by what they carry: lines that users can be fed off. */
        final Map<Integer, IntSeq> carry = new HashMap<>();
        int failed, x, y, free;
        final List<String> why = new ArrayList<>();

        void plan(Part p){
            ground.plan(p);
            planned.add(p);
        }

        void plan(Seq<Part> ps){
            for(Part p : ps) plan(p);
        }

        void keep(int pos){
            if(ground.kept.add(pos)) kept.add(pos);
        }

        void undo(){
            ground.unplan(planned);
            for(int i = 0; i < kept.size; i++) ground.kept.remove(kept.get(i));
            for(Machine m : placed){
                at.remove(m);
                nodes.remove(m);
                coreLine.remove(m);
            }
        }
    }

    void layBase(List<Machine> order){
        List<Machine> furnaces = new ArrayList<>(), factories = new ArrayList<>(), refabs = new ArrayList<>(), electrolyzers = new ArrayList<>(), others = new ArrayList<>();
        for(Machine m : order){
            if(m.recipe.ground != Recipe.Ground.none) continue;
            Recipe r = m.recipe;
            if(r.unitIn >= 0) refabs.add(m);
            else if(r.makesUnits()) factories.add(m);
            else if(r.out[cat.id("silicon")] > 0f) furnaces.add(m);
            else if(r.out[cat.id("hydrogen")] > 0f) electrolyzers.add(m);
            else others.add(m);
        }
        //where what feeds the first furnace stands, to put its cell towards that
        float sx = 0f, sy = 0f;
        int n = 0;
        for(Flow f : flows){
            if(!furnaces.isEmpty() && f.to == furnaces.get(0) && f.from.recipe.ground != Recipe.Ground.none){
                sx += f.from.x;
                sy += f.from.y;
                n++;
            }
        }
        float fx = n == 0 ? field.coreX : sx / n, fy = n == 0 ? field.coreY : sy / n;

        Attempt best = null;
        int tries = 0;
        Block fb = furnaces.isEmpty() ? null : block(furnaces.get(0));
        List<int[]> spots = fb == null ? new ArrayList<>(List.of(new int[]{field.coreX, field.coreY})) : near(fb, 5, 45);
        //nearer the core and nearer what feeds the furnace both count
        float cx = field.coreX, cy = field.coreY;
        spots.sort(Comparator.comparingDouble(c -> Math.hypot(c[0] - cx, c[1] - cy) + 0.7 * Math.hypot(c[0] - fx, c[1] - fy)));
        for(int[] spot : spots){
            if(tries >= 50) break;
            for(int free = 0; free < 4; free++){
                if(fb != null && !ground.clear(fb, spot[0], spot[1])) break;
                tries++;
                Attempt a = attempt(spot[0], spot[1], free, furnaces, factories, refabs, electrolyzers, others);
                if(a == null) continue;
                if(a.failed == 0){
                    best = keepAttempt(best, a);
                    log.append("cells at ").append(spot[0]).append(',').append(spot[1]).append(" after ").append(tries).append(" tries, every line laid\n");
                    return;
                }
                a.undo();
                if(best == null || a.failed < best.failed) best = a;
                if(fb == null) break;
            }
            if(fb == null) break;
        }
        if(best == null){
            log.append("no room for the cells anywhere\n");
            return;
        }
        //none had every line: the one with fewest left out is put down again
        log.append(String.format("no place with every line; using the one %d short: %s\n", best.failed, String.join("; ", best.why)));
        Attempt again = attempt(best.x, best.y, best.free, furnaces, factories, refabs, electrolyzers, others);
        if(again != null) keepAttempt(null, again);
    }

    Attempt keepAttempt(Attempt old, Attempt a){
        for(var e : a.lines.entrySet()) laid.computeIfAbsent(e.getKey(), k -> new Seq<>()).addAll(e.getValue());
        return a;
    }

    /** One way of putting the cells down, with all their lines worked out. Null if the cells themselves do not fit. */
    Attempt attempt(int x, int y, int free, List<Machine> furnaces, List<Machine> factories, List<Machine> refabs, List<Machine> electrolyzers, List<Machine> others){
        Attempt a = new Attempt();
        a.x = x;
        a.y = y;
        a.free = free;
        List<Machine> alone = new ArrayList<>(factories);
        //the first furnace with up to three factories on the sides but the free one
        if(!furnaces.isEmpty()){
            Machine furnace = furnaces.get(0);
            Block fb = block(furnace);
            place(a, furnace, new Part(fb, x, y, 0, null));
            int side = 0;
            for(Machine f : factories){
                if(side == free) side++;
                if(side > 3) break;
                Block b = block(f);
                Part p = new Part(b, x + Geometry.d4x(side) * 3, y + Geometry.d4y(side) * 3, side, null);
                if(ground.clear(b, p.x, p.y) && frontOpen(p)){
                    place(a, f, p);
                    keepFront(a, p);
                    alone.remove(f);
                }
                side++;
            }
            //the free side has to be open for the lines in and out
            if(!ground.open(x + Geometry.d4x(free) * 2, y + Geometry.d4y(free) * 2)){
                a.undo();
                return null;
            }
            for(int i = 1; i < furnaces.size(); i++) single(a, furnaces.get(i), 0);
        }
        for(Machine f : alone) single(a, f, -1);
        //a refabricator goes in front of a factory making what it takes; the factory hands its units straight in
        Set<Machine> fronted = new HashSet<>();
        for(Machine r : refabs){
            boolean placed = false;
            for(Machine f : factories){
                Part fp = at.get(f);
                if(fronted.contains(f) || fp == null || f.recipe.unitOut != r.recipe.unitIn) continue;
                int d = fp.rotation, rx = fp.x + Geometry.d4x(d) * 3, ry = fp.y + Geometry.d4y(d) * 3;
                IntSeq front = front(fp);
                for(int i = 0; i < front.size; i++) ground.kept.remove(front.get(i));
                Block b = block(r);
                Part rp = new Part(b, rx, ry, d, null);
                if(ground.clear(b, rx, ry) && frontOpen(rp)){
                    place(a, r, rp);
                    keepFront(a, rp);
                    fronted.add(f);
                    placed = true;
                    break;
                }
                for(int i = 0; i < front.size; i++) ground.kept.add(front.get(i));
            }
            if(!placed) single(a, r, -1);
        }
        //an electrolyzer against a refabricator, hydrogen side towards it
        for(Machine e : electrolyzers){
            boolean placed = false;
            Block b = block(e);
            for(Machine r : refabs){
                Part rp = at.get(r);
                if(rp == null) continue;
                for(int side = 0; side < 4 && !placed; side++){
                    if(side == rp.rotation || side == (rp.rotation + 2) % 4) continue;
                    int ex = rp.x + Geometry.d4x(side) * 3, ey = rp.y + Geometry.d4y(side) * 3;
                    //its hydrogen comes out of side (rotation + 3), which has to face back to the refabricator
                    int rot = (side + 3) % 4;
                    if(ground.clear(b, ex, ey)){
                        place(a, e, new Part(b, ex, ey, rot, null));
                        placed = true;
                    }
                }
                if(placed) break;
            }
            if(!placed) single(a, e, 0);
        }
        for(Machine m : others) single(a, m, 0);
        for(Machine m : a.placed) reserveNode(a, m);
        for(Machine m : order){
            if(m.recipe.ground == Recipe.Ground.none && !at.containsKey(m)){
                a.failed += 10;
                a.why.add("no room for " + m.recipe.name);
            }
        }
        routeAll(a);
        return a;
    }

    void place(Attempt a, Machine m, Part p){
        at.put(m, p);
        a.placed.add(m);
        a.plan(p);
    }

    /** The first piece of each crafter's line to the core, which is taken up once everything that costs what it makes is built. */
    public final Map<Machine, Part> coreLine = new HashMap<>();

    /** Where each powered machine's own node goes, kept from the moment the machine is placed so that lines leave room for it. */
    final Map<Machine, Part> nodes = new HashMap<>();

    void reserveNode(Attempt a, Machine m){
        Part p = at.get(m);
        if(p == null || (m.recipe.powerUse <= 0f && m.powerOut() <= 0f)) return;
        //a corner of its ring first: a side tile is where a line would most want to go
        Part node = null;
        List<int[]> ring = around(p);
        for(int pass = 0; pass < 2 && node == null; pass++){
            for(int i = 0; i < ring.size(); i++){
                int[] t = ring.get(i);
                boolean corner = i < 4 || i >= ring.size() - 4;
                if((pass == 0) != corner || !ground.open(t[0], t[1])) continue;
                node = new Part(Blocks.beamNode, t[0], t[1], 0, null);
                break;
            }
        }
        if(node == null) return;
        if(a != null) a.plan(node);
        else ground.plan(node);
        nodes.put(m, node);
    }

    /** Anything else, alone, near the core. A unit factory faces away from the core. */
    void single(Attempt a, Machine m, int rotation){
        Block b = block(m);
        for(int[] c : near(b, 5, 50)){
            int rot = rotation >= 0 ? rotation : away(c[0], c[1]);
            Part p = new Part(b, c[0], c[1], rot, null);
            if(ground.clear(b, c[0], c[1]) && (!m.recipe.makesUnits() || frontOpen(p))){
                place(a, m, p);
                if(m.recipe.makesUnits()) keepFront(a, p);
                return;
            }
        }
    }

    /** Every line the flows need, worked out and kept on the ground; the ones that could not be found are counted. */
    void routeAll(Attempt a){
        //in the order they will be built, so that a line only ever joins one that will be there before it
        List<Object[]> tasks = new ArrayList<>();
        for(Flow f : flows) tasks.add(new Object[]{f.from.existing ? f.to : f.from, f, -1});
        for(var e : taps.entrySet()) for(int k : e.getValue()) tasks.add(new Object[]{e.getKey(), null, k});
        tasks.sort(Comparator.comparingInt(t -> order.indexOf((Machine)t[0])));
        for(Object[] task : tasks){
            Machine job = (Machine)task[0];
            Flow f = (Flow)task[1];
            Lines.Line line;
            String what;
            if(f != null){
                Part from = f.from.existing ? new Part(block(f.from), f.from.x, f.from.y, f.from.rotation, null) : at.get(f.from);
                Part to = f.to == null ? null : at.get(f.to);
                if(from == null || (f.to != null && to == null)) continue;
                if(to != null && touching(from, to)) continue;
                Lines with = cat.get(f.res).kind == Res.Kind.liquid ? pipes : lines;
                Lines.Goal goal = to == null ? Lines.Goal.core(joinable(f.res)) : Lines.Goal.into(tiles(to), to.x, to.y, joining(a, f.res, f.to));
                line = out(with, from, tiles(from), goal);
                what = String.format("%s from %s to %s", cat.get(f.res).name, f.from.recipe.name, f.to == null ? "the core" : f.to.recipe.name);
                if(line != null && to == null && with == lines){
                    IntSeq c = a.carry.computeIfAbsent(f.res, x -> new IntSeq());
                    for(Part p : line.parts) if(p.block == Blocks.duct) c.add(Point2.pack(p.x, p.y));
                    //a crafter that feeds factories it touches keeps its line to the core only while there is building to do
                    if(f.from.recipe.ground == Recipe.Ground.none && line.parts.size > 0) coreLine.put(f.from, line.parts.first());
                }
                if(line != null && to != null) a.into.computeIfAbsent(f.res + ">" + System.identityHashCode(f.to), k -> new IntSet()).addAll(tilesOf(line.parts));
            }else{
                int k = (Integer)task[2];
                Part cp = at.get(job);
                if(cp == null) continue;
                line = tap(k, cp, tiles(cp), a.carry.get(k));
                what = String.format("%s off a running line for %s", cat.get(k).name, job.recipe.name);
            }
            if(line == null){
                a.failed++;
                a.why.add(what);
                continue;
            }
            a.plan(line.parts);
            a.lines.computeIfAbsent(job, x -> new Seq<>()).addAll(line.parts);
        }
    }

    IntSet joining(Attempt a, int res, Machine to){
        return a.into.get(res + ">" + System.identityHashCode(to));
    }

    /** Running lines that carry a thing to the core, which a new line of it to the core may join. */
    IntSet joinable(int res){
        IntSet out = new IntSet();
        for(var e : carries) if(e.value == res) out.add(e.key);
        return out.isEmpty() ? null : out;
    }

    static IntSet tilesOf(Seq<Part> parts){
        IntSet out = new IntSet();
        for(Part p : parts) out.add(Point2.pack(p.x, p.y));
        return out;
    }

    int away(int x, int y){
        int dx = x - field.coreX, dy = y - field.coreY;
        return Math.abs(dx) >= Math.abs(dy) ? (dx >= 0 ? 0 : 2) : (dy >= 0 ? 1 : 3);
    }

    /** Tiles round the core from near to far, at least so far from it that lines can still get to the core between. */
    List<int[]> near(Block b, int min, int max){
        List<int[]> list = new ArrayList<>();
        for(int dx = -max; dx <= max; dx++){
            for(int dy = -max; dy <= max; dy++){
                float d = (float)Math.hypot(dx, dy);
                if(d < min + b.size / 2f || d > max) continue;
                list.add(new int[]{field.coreX + dx, field.coreY + dy, (int)(d * 100)});
            }
        }
        list.sort(Comparator.comparingInt(c -> c[2]));
        return list;
    }

    /** The row in front of a unit factory, where its units come out, and the one beyond. */
    IntSeq front(Part p){
        IntSeq out = new IntSeq();
        int d = p.rotation, half = p.block.size / 2;
        for(int depth = 1; depth <= 2; depth++){
            for(int across = -half; across <= half; across++){
                int x = p.x + Geometry.d4x(d) * (half + depth) + Geometry.d4x((d + 1) % 4) * across;
                int y = p.y + Geometry.d4y(d) * (half + depth) + Geometry.d4y((d + 1) % 4) * across;
                out.add(Point2.pack(x, y));
            }
        }
        return out;
    }

    boolean frontOpen(Part p){
        IntSeq f = front(p);
        for(int i = 0; i < f.size; i++) if(!ground.open(Point2.x(f.get(i)), Point2.y(f.get(i)))) return false;
        return true;
    }

    void keepFront(Attempt a, Part p){
        IntSeq f = front(p);
        for(int i = 0; i < f.size; i++) a.keep(f.get(i));
    }

    // ---------------------------------------------------------------- when a machine's turn comes

    /** What to build for a machine: the machine, the lines worked out for it, and the node that powers it with the chain to a grid. */
    public Seq<Part> job(Machine m){
        Seq<Part> parts = new Seq<>();
        Part self = at.get(m);
        if(self == null) return parts;
        parts.add(self);
        for(Part p : laid.getOrDefault(m, new Seq<>())) parts.add(p);
        if(m.recipe.powerUse > 0f || m.powerOut() > 0f){
            Part node = nodes.get(m);
            if(node == null){
                node = nodeBeside(self);
                if(node != null) ground.plan(node);
            }
            if(node == null){
                log.append("no room for a node by ").append(m.recipe.name).append('\n');
            }else{
                parts.add(node);
                Seq<Part> mine = Seq.with(self, node);
                Seq<Part> chain = m.recipe.powerUse > 0f ? power.link(mine, m.recipe.powerUse / 60f, new IntSet()) : power.join(mine, powered(), new IntSet());
                //far from any grid: joined straight to the nearest generator of the plan's, which was put there for it
                if(chain == null && m.recipe.powerUse > 0f) chain = power.join(mine, generatorsNear(self), new IntSet());
                if(chain == null) log.append("no way to power ").append(m.recipe.name).append(" at ").append(self.x).append(',').append(self.y).append('\n');
                else{
                    ground.plan(chain);
                    parts.addAll(chain);
                }
            }
        }
        return parts;
    }

    /**
     * The shortest line out of a block, from any open tile against it. A conduit takes liquid from anything but another
     * conduit only when it points straight away from it, so a pipe starts facing away from the block.
     */
    Lines.Line out(Lines with, Part from, IntSet body, Lines.Goal goal){
        Lines.Line best = null;
        for(int[] t : around(from)){
            if(!ground.open(t[0], t[1])) continue;
            int facing = with.liquid ? awayFrom(from, t[0], t[1]) : -1;
            Lines.Line line = with.route(t[0], t[1], facing, body, goal);
            if(line != null && (best == null || line.parts.size < best.parts.size)) best = line;
        }
        return best;
    }

    /** The way that points from a block's edge out through a tile just outside it. */
    static int awayFrom(Part p, int x, int y){
        int s = p.block.size, off = -(s - 1) / 2;
        int x0 = p.x + off, y0 = p.y + off, x1 = x0 + s - 1, y1 = y0 + s - 1;
        if(x > x1) return 0;
        if(y > y1) return 1;
        if(x < x0) return 2;
        return 3;
    }

    /** A line taken off the nearest running duct that carries the item, into the block. */
    Lines.Line tap(int res, Part into, IntSet body, IntSeq planned){
        IntSeq ducts = new IntSeq();
        for(var e : carries) if(e.value == res) ducts.add(e.key);
        if(planned != null) ducts.addAll(planned);
        Integer[] sorted = new Integer[ducts.size];
        for(int i = 0; i < ducts.size; i++) sorted[i] = ducts.get(i);
        Arrays.sort(sorted, Comparator.comparingDouble(pos -> Math.hypot(Point2.x(pos) - into.x, Point2.y(pos) - into.y)));
        for(int i = 0; i < Math.min(40, sorted.length); i++){
            Lines.Line line = lines.tap(Point2.x(sorted[i]), Point2.y(sorted[i]), Lines.Goal.into(body, into.x, into.y, null));
            if(line != null) return line;
        }
        return null;
    }

    /** The plan's generators that stand already, nearest first, with their nodes. */
    Seq<Part> generatorsNear(Part p){
        Seq<Part> out = new Seq<>();
        for(Machine m : order){
            Part g = at.get(m);
            if(m.powerOut() <= 0f || g == null || Vars.world.tile(g.x, g.y).block() != g.block) continue;
            if(Math.hypot(g.x - p.x, g.y - p.y) > 120) continue;
            out.add(g);
            Part n = nodes.get(m);
            if(n != null) out.add(n);
        }
        return out;
    }

    /** Parts for everything of the team's that uses power, for joining a generator to. */
    Seq<Part> powered(){
        Seq<Part> out = new Seq<>();
        for(Building b : team.data().buildings){
            if(b.block.hasPower && b.block.consPower != null && b.tile == Vars.world.tile(b.tileX(), b.tileY())) out.add(new Part(b.block, b.tileX(), b.tileY(), b.rotation, null));
        }
        return out;
    }

    Part nodeBeside(Part p){
        for(int[] t : around(p)){
            if(ground.open(t[0], t[1])) return new Part(Blocks.beamNode, t[0], t[1], 0, null);
        }
        return null;
    }

    /** The tiles just outside a block's edge. */
    static List<int[]> around(Part p){
        List<int[]> out = new ArrayList<>();
        int s = p.block.size, off = -(s - 1) / 2;
        for(int i = 0; i < s; i++){
            out.add(new int[]{p.x + off + i, p.y + off - 1});
            out.add(new int[]{p.x + off + i, p.y + off + s});
            out.add(new int[]{p.x + off - 1, p.y + off + i});
            out.add(new int[]{p.x + off + s, p.y + off + i});
        }
        return out;
    }

    static IntSet tiles(Part p){
        IntSet out = new IntSet();
        Vars.world.tile(p.x, p.y).getLinkedTilesAs(p.block, t -> out.add(t.pos()));
        return out;
    }

    static boolean touching(Part a, Part b){
        IntSet tb = tiles(b);
        for(int[] t : around(a)) if(tb.contains(Point2.pack(t[0], t[1]))) return true;
        return false;
    }
}
