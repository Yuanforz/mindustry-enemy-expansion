package expansion;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import expansion.Blueprint.*;
import expansion.Net.Thing;
import expansion.Territory.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mindustry.world.blocks.units.*;

import static mindustry.Vars.*;

/**
 * Works out whole pieces of a base: a mine with its line to the core and its power, a furnace with
 * the crushers that feed it, a unit yard or a turret with the lines that supply it.
 *
 * Everything is planned on paper first. The pieces come from blueprints, the fitter finds where they
 * go, the lines and the power are laid to their ports, and only when all of it works out is any of it
 * handed over to be built. If one step fails the whole attempt is dropped and nothing is left behind.
 */
public class Planner{
    /** How much of what something uses has to be going by on a line, as far as it has not been led off already, for the line to supply it. */
    static final float enough = 0.75f;
    /** What a duct carries, with some room left: lines are not joined beyond this many items a second. */
    static final float lineLimit = 12f;

    private final Brain brain;
    private final Ground ground;
    private final Fitter fitter;
    private final Lines lines, pipes;
    private final Power power;
    private final Net net;
    private final Territory territory;

    private final IntSet tiles = new IntSet(), none = new IntSet();
    /** Why the last attempt at each kind of thing came to nothing. */
    public String note = "";
    /**
     * How long working one thing out may take, in milliseconds. It all happens between two frames of the
     * game, so when the time is up the attempt is dropped where it stands and taken up again later.
     */
    public float timeLimit = 25f;
    private long deadline = Long.MAX_VALUE;

    /** Whether the last attempt was dropped because its time was up, and not because there was no way. */
    public boolean timedOut;

    /**
     * A liquid the last attempt could not get, and where it was wanted. What needed it is put off until
     * something that makes it has been built near there.
     */
    public @Nullable Liquid lacking;
    public int lackX, lackY;

    /**
     * Where the next line from a mine or a factory is wanted, if anywhere in particular: the tiles of a
     * line it should join, and the place that line supplies, to steer by.
     */
    public @Nullable IntSet toward;
    public int towardX, towardY;
    /**
     * The tiles of something going short of an item that no line of it can be led to: a new source of
     * the item then has its line run straight into that, and nowhere else. One item on it, so it never
     * stops for something else; when that thing is full the drills wait.
     */
    public @Nullable IntSet into;

    /** Starts the clock for working one thing out. */
    public void begin(){
        deadline = Clock.now() + (long)(timeLimit * 1e6f);
        lines.deadline = pipes.deadline = fitter.deadline = power.deadline = deadline;
        timedOut = false;
        lacking = null;
        fitter.unseenAt = -1;
    }

    /** Whether the time for working things out this round is up. */
    public boolean late(){
        if(Clock.now() <= deadline) return false;
        timedOut = true;
        return true;
    }
    /** The last layout that was tried, for looking into what went wrong with it. */
    public @Nullable Layout lastTried;
    public @Nullable Seq<int[]> lastSpots;
    /** Called with a layout at the moment a line to it could not be laid. For looking into why. */
    public @Nullable arc.func.Cons<Layout> onFeedFailed;

    /** Something worked out and ready to be built. */
    public static class Project{
        public final String name;
        /** In the order they should go up. */
        public final Seq<Part> parts = new Seq<>();
        /** Put up last: these replace ducts of lines that are in use. */
        public final Seq<Part> last = new Seq<>();
        /** Walls of the enemy's own that are in the way and come down first. */
        public final IntSeq removals = new IntSeq();
        public final Seq<Thing> things = new Seq<>();
        /** What this adds to the base, taken as wholes: noted so that each can be made better later. */
        public final Seq<Asset> assets = new Seq<>();
        /** Something that stands already which this lets a liquid into, and which liquid. */
        public @Nullable Asset boosts;
        public @Nullable Liquid boostWith;
        /** For a turret that is given better ammunition: what it is from now on. */
        public @Nullable Item armWith;
        /** Notes of the net that no longer hold once this is laid out. */
        public final Seq<Thing> forget = new Seq<>();
        /** Duct that a line runs over now and will not once this is built: taken up when the duct at {@link #turn} stands as this has it. */
        public final IntSeq retire = new IntSeq();
        public @Nullable Part turn;
        /** Laid out to hold off an attack that is going on: built before anything else. */
        public boolean urgent;
        /** A first source of something it can't do without: built before anything else but what holds off an attack. */
        public boolean first;

        Project(String name){
            this.name = name;
        }

        public ItemStack[] cost(){
            ItemSeq total = new ItemSeq();
            for(Part part : parts) total.add(part.block.requirements);
            for(Part part : last) total.add(part.block.requirements);
            return ItemStack.mult(total.toArray(), state.rules.buildCostMultiplier);
        }
    }

    public Planner(Brain brain){
        this.brain = brain;
        ground = brain.ground;
        fitter = brain.fitter;
        lines = brain.lines;
        pipes = brain.pipes;
        power = brain.power;
        net = brain.net;
        territory = brain.territory;
    }

    //region pieces

    private void add(Project project, Seq<Part> parts){
        ground.plan(parts);
        project.parts.addAll(parts);
    }

    private void add(Project project, Layout layout){
        add(project, layout.parts);
        //the ground between a drill and its ore stays clear, and the ground a yard's units leave by: the lines and
        //the power laid to it next are not to run across that
        tiles.clear();
        for(Part part : layout.parts) Shape.tiles(part, tiles);
        for(Part part : layout.parts){
            if(part.block instanceof BeamDrill drill) brain.keepBeams(drill, part.x, part.y, part.rotation);
            if(!(part.block instanceof UnitBlock)) continue;
            front.clear();
            brain.frontOf(part.block, part.x, part.y, part.rotation, front);
            for(int i = 0; i < front.size; i++){
                if(!tiles.contains(front.get(i))) ground.kept.add(front.get(i));
            }
        }
    }

    /** Drops an attempt: what it had planned no longer counts as standing. */
    @Nullable Project drop(Project project, String why){
        note = project.name + ": " + why;
        brain.syncGround();
        return null;
    }

    private IntSet footprint(Seq<Part> parts){
        IntSet out = new IntSet();
        for(Part part : parts) world.tile(part.x, part.y).getLinkedTilesAs(part.block, tile -> out.add(tile.pos()));
        return out;
    }

    /**
     * The enemy's own lines that another one could empty into: ones that carry the same item and
     * nothing else, and would not be overfilled. A line that carries one thing only can be shared out
     * with plain routers, as the bases on the game's own maps do; let something else onto it and
     * whatever was led off it for a furnace or a turret fills up with what those will not take, and stops.
     * Even a line that only runs into a core is not mixed: a core that is full of one thing and does not
     * burn what it has no room for takes no more of it, and the whole line stops behind that one item.
     */
    private IntSet joinable(float adding, Item item){
        IntSet out = new IntSet();
        if(item.id >= 31) return out;
        IntIntMap mix = net.mix();
        IntFloatMap rates = net.lines();
        for(var entry : rates){
            if(entry.value + adding <= lineLimit && mix.get(entry.key, 0) == 1 << item.id) out.add(entry.key);
        }
        return out;
    }

    /**
     * A line to a core for the first source of something it can't do without, looked for harder than
     * any other: four times the places, and twice the time again, that one search may otherwise take. In a
     * base that has grown large the way home is long and crooked, and without this line whatever
     * needs the item can never be built. It happens seldom, and is worth a hitch.
     */
    private @Nullable Lines.Line routeHome(int x, int y, IntSet feeders, IntSet joins){
        if(!needy) return lines.route(x, y, feeders, Lines.Goal.core(joins));
        int budget = lines.budget, allowance = lines.allowance;
        long limit = lines.deadline;
        lines.budget = budget * 4;
        lines.allowance = Math.max(allowance, lines.budget);
        lines.deadline = Math.max(limit, Clock.now()) + (long)(timeLimit * 2e6f);
        try{
            return lines.route(x, y, feeders, Lines.Goal.core(joins));
        }finally{
            lines.budget = budget;
            lines.allowance = Math.max(allowance - lines.lastLooked, 0);
            lines.deadline = limit;
        }
    }

    /** Lays a line from an output to the nearest core, or to one of the enemy's lines that runs to one. */
    private boolean lead(Project project, Layout layout, Net.Kind kind, Item item, float rate){
        Port port = layout.port(Role.out, null);
        if(port == null) return false;
        IntSet joins = joinable(rate, item);
        Lines.Line line = null;
        if(into != null) line = lines.route(port.x, port.y, footprint(layout.parts), Lines.Goal.into(into, towardX, towardY, null));
        if(line == null && toward != null){
            //wanted somewhere in particular: on the line that supplies something that is going short
            IntSet there = new IntSet();
            IntSet.IntSetIterator each = toward.iterator();
            while(each.hasNext){
                int pos = each.next();
                if(joins.contains(pos)) there.add(pos);
            }
            if(!there.isEmpty()) line = lines.route(port.x, port.y, footprint(layout.parts), Lines.Goal.into(none, towardX, towardY, there));
        }
        if(line == null) line = routeHome(port.x, port.y, footprint(layout.parts), joins);
        if(line == null) return false;
        add(project, line.parts);
        project.removals.addAll(line.removals);
        project.things.add(new Thing(kind, port.x, port.y, item, rate));
        return true;
    }

    /**
     * Brings an item to an input: straight from a factory of the enemy's that makes it, by a line of
     * its own from one of the factory's free sides, or else by a branch off a line that carries it.
     * Whichever is nearest the input is tried first.
     */
    private boolean feed(Project project, Port port, float rate){
        return feed(project, port.item, port.x, port.y, rate, Lines.Goal.port(port), port);
    }

    /**
     * Brings an item to a place by a line led off wherever enough of it goes by.
     * @param goal where the line has to end: at an input, or at the side of a line that runs on to one
     * @param port the input, if that is where it ends: a factory that makes the item may then lead a line of its own there
     */
    private boolean feed(Project project, Item item, int toX, int toY, float rate, Lines.Goal goal, @Nullable Port port){
        int feedLimit = Options.feedLimit.get();
        //x, y, and what it is: a factory to lead a line from (2), a place on one of the enemy's own lines (1), or on a line its base came with (0)
        Seq<int[]> places = new Seq<>();
        for(var entry : net.carried(item)){
            //there has to be a fair part of what is wanted going by. every third tile will do: the ones between are no different
            int x = Point2.x(entry.key), y = Point2.y(entry.key);
            if(entry.value >= rate * enough && (x + y) % 3 == 0) places.add(new int[]{x, y, 1, (int)(entry.value * 100)});
        }
        for(Thing thing : net.things){
            if(thing.kind == Net.Kind.maker && thing.item == item) places.add(new int[]{thing.x, thing.y, 2});
        }
        IntSet.IntSetIterator seen = brain.seen(item).iterator();
        while(seen.hasNext){
            int pos = seen.next(), x = Point2.x(pos), y = Point2.y(pos);
            if((x + y) % 3 == 0) places.add(new int[]{x, y, 0});
        }
        if(places.isEmpty()) return false;
        //how far each is by the way a line would have to go: the nearest on the map may be shut in behind other lines.
        //the ground is gone over once from where the line is to end, and a place on a line is as far as the open tile at its side
        IntSeq ends = new IntSeq();
        if(goal.port){
            ends.add(Point2.pack(goal.portX, goal.portY));
        }else if(goal.into != null){
            IntSet.IntSetIterator each = goal.into.iterator();
            while(each.hasNext){
                int pos = each.next();
                for(int d = 0; d < 4; d++) ends.add(Point2.pack(Point2.x(pos) + Geometry.d4x(d), Point2.y(pos) + Geometry.d4y(d)));
            }
        }
        IntIntMap steps = ends.isEmpty() ? new IntIntMap() : lines.reachFrom(ends, feedLimit + 6);
        boolean known = steps.size > 1;
        ObjectIntMap<int[]> far = new ObjectIntMap<>();
        for(int[] at : places){
            int least = Integer.MAX_VALUE;
            if(known && at[2] != 2){
                for(int d = 0; d < 4; d++) least = Math.min(least, steps.get(Point2.pack(at[0] + Geometry.d4x(d), at[1] + Geometry.d4y(d)), Integer.MAX_VALUE));
            }else{
                least = Math.abs(at[0] - toX) + Math.abs(at[1] - toY);
            }
            far.put(at, least);
        }
        int before = places.size;
        places.removeAll(at -> far.get(at, 0) == Integer.MAX_VALUE);
        //nearest first, and its own lines before the ones it found: it knows what those carry. and of those near, the one that
        //carries most: what takes in bursts, as a turret in a fight, empties a thin line at once (the turrets at a sector's
        //south gate ran dry in five seconds of the last wave, each off a line one drill fed)
        places.sort(at -> far.get(at, 0) - at[2] * 6 - (at.length > 3 ? at[3] / 100f * 8f : 0f));

        int tries = 0;
        feedNote = before + " places on lines with it, " + places.size + " that a line could get to";
        //places a branch could not get away from. lines run to a core side by side, and the ground between them is shut in for a long way:
        //the next place tried is one well clear of those
        IntSeq shut = new IntSeq();
        IntIntMap mix = net.mix();
        int only = item.id < 31 ? 1 << item.id : 0;
        for(int[] at : places){
            if(far.get(at, 0) > feedLimit || tries >= 8 || late()) break;
            if(at[2] == 2){
                tries++;
                if((port != null && direct(project, at[0], at[1], port, rate))) return true;
                continue;
            }
            boolean near = false;
            for(int i = 0; i < shut.size && !near; i += 2) near = Math.max(Math.abs(shut.get(i) - at[0]), Math.abs(shut.get(i + 1) - at[1])) <= 8;
            //a line of its own that carries nothing else is shared out with a plain router; a mixed one, or one it only
            //knows by what it saw go by, has the item sorted out of it
            boolean pure = at[2] == 1 && only != 0 && mix.get(Point2.pack(at[0], at[1]), only) == only;
            if(near || !((pure && lines.taps(at[0], at[1])) || lines.branches(at[0], at[1]))) continue;
            tries++;
            Lines.Line line = pure ? lines.tap(at[0], at[1], goal) : null;
            if(line == null) line = lines.branch(at[0], at[1], item, goal);
            if(line == null) shut.add(at[0], at[1]);
            feedNote += "; from " + at[0] + "," + at[1] + (line == null ? " no way (looked at " + lines.lastLooked + ": built on " + lines.shutBy[1] + ", closed " + lines.shutBy[0] + ", pushed into " + lines.shutBy[2] + ", no bridge " + lines.shutBy[3] + ")" : " " + line.parts.size + " long");
            //looked everywhere it could from close by and did not get there: the input is shut in, and no other start will do better
            if(line == null && lines.lastLooked >= lines.shortBudget && Math.abs(at[0] - toX) + Math.abs(at[1] - toY) < 16) break;
            if(line == null || line.parts.size > feedLimit + 3) continue;
            //the router that goes where a duct of the line stands is put up last, so the line runs until the branch is ready
            project.last.add(line.parts.first());
            ground.plan(line.parts);
            for(int i = 1; i < line.parts.size; i++) project.parts.add(line.parts.get(i));
            project.things.add(new Thing(Net.Kind.branch, at[0], at[1], item, rate));
            return true;
        }
        return false;
    }

    /**
     * Leads a line of its own from a factory to an input. A factory hands what it makes to everything
     * next to it that takes it, in turn, so a second line simply gets its share, and when that line is
     * full the first gets everything again.
     * @param x where the factory's line to the core starts: the factory is the thing next to that
     */
    private boolean direct(Project project, int x, int y, Port port, float rate){
        Block factory = null;
        int fx = 0, fy = 0;
        for(int d = 0; d < 4 && factory == null; d++){
            if(ground.look(x + Geometry.d4x(d), y + Geometry.d4y(d)) && Rates.makes(ground.block, port.item) > 0f){
                factory = ground.block;
                fx = ground.originX;
                fy = ground.originY;
            }
        }
        if(factory == null) return false;
        //it must make nothing else, or the line would carry that too
        if(((GenericCrafter)factory).outputItems.length != 1) return false;

        tiles.clear();
        world.tile(fx, fy).getLinkedTilesAs(factory, tile -> tiles.add(tile.pos()));
        int offset = -(factory.size - 1) / 2, size = factory.size;
        Seq<int[]> starts = new Seq<>();
        for(int i = 0; i < size; i++){
            for(int side = 0; side < 4; side++){
                int sx = fx + offset + (side == 0 ? size : side == 2 ? -1 : i), sy = fy + offset + (side == 1 ? size : side == 3 ? -1 : i);
                if(ground.open(sx, sy)) starts.add(new int[]{sx, sy});
            }
        }
        starts.sort(at -> Math.abs(at[0] - port.x) + Math.abs(at[1] - port.y));

        for(int i = 0; i < Math.min(starts.size, 3); i++){
            Lines.Line line = lines.route(starts.get(i)[0], starts.get(i)[1], tiles, Lines.Goal.port(port));
            if(line == null || line.parts.size > Options.feedLimit.get()) continue;
            add(project, line.parts);
            //counted against what the factory puts on its line to the core
            project.things.add(new Thing(Net.Kind.branch, x, y, port.item, rate));
            return true;
        }
        return false;
    }

    /** Gets power to everything in a project that uses it. The groups are joined to each other, and the first to a grid or generators. */
    private boolean energize(Project project, Seq<Seq<Part>> groups, float needed){
        if(needed <= 0f || groups.isEmpty()) return true;
        power.essential = needy || firstOfKind;
        try{
            return wire(project, groups, needed);
        }finally{
            power.essential = false;
        }
    }

    /**
     * Whether what is being worked out is the first unit yard it has, or its first factory for
     * something: that is worth putting on a loaded grid. The second and the third are not: they would
     * only slow down what is on it already, by as much as they add.
     */
    private boolean firstOfKind;

    private boolean wire(Project project, Seq<Seq<Part>> groups, float needed){
        Seq<Part> first = groups.first();
        Seq<Part> extra = power.link(first, needed, none);
        if(extra == null) return false;
        ground.plan(extra);
        Seq<Part> all = new Seq<>(first);
        all.addAll(extra);
        for(int i = 1; i < groups.size; i++){
            Seq<Part> chain = power.join(groups.get(i), all, none);
            if(chain == null) return false;
            ground.plan(chain);
            extra.addAll(chain);
            all.addAll(groups.get(i));
            all.addAll(chain);
        }
        //power goes up first: nothing else works without it
        Seq<Part> rest = new Seq<>(project.parts);
        project.parts.clear();
        project.parts.addAll(extra);
        project.parts.addAll(rest);
        return true;
    }

    //endregion
    //region liquids

    /** Something that has a liquid to give: the block, the side it comes out of, and how much is not spoken for. */
    private static class Well{
        Part part;
        int side;
        float spare;
    }

    /**
     * Everything of the enemy's that makes a liquid and has some to spare: what it has built or
     * planned for the purpose, and whatever else stands in its base brim-full of it, with nothing
     * taking it away.
     */
    private Seq<Well> wells(Liquid liquid, int x, int y){
        Seq<Well> out = new Seq<>();
        IntSet known = new IntSet();
        for(Thing thing : net.things){
            if(thing.kind != Net.Kind.spring || thing.liquid != liquid || !ground.look(thing.x, thing.y)) continue;
            known.add(Point2.pack(thing.x, thing.y));
            well(out, new Part(ground.block, ground.originX, ground.originY, ground.rotation), liquid);
        }
        int limit = reachOfPipes();
        for(Building build : ground.team.data().buildings){
            if(build.liquids == null || known.contains(Point2.pack(build.tileX(), build.tileY())) || Math.abs(build.tileX() - x) + Math.abs(build.tileY() - y) > limit) continue;
            if(!Ground.givesLiquid(build.block) || build.liquids.get(liquid) < build.block.liquidCapacity * 0.9f || !ground.rule.allows(build.tileX(), build.tileY())) continue;
            well(out, new Part(build.block, build.tileX(), build.tileY(), build.rotation), liquid);
        }
        out.sort(well -> Math.abs(well.part.x - x) + Math.abs(well.part.y - y));
        return out;
    }

    private void well(Seq<Well> out, Part part, Liquid liquid){
        for(Rates.Flow flow : Rates.liquidGives(part.block)){
            if(flow.liquid != liquid) continue;
            Well well = new Well();
            well.part = part;
            well.side = flow.side == -1 ? -1 : (flow.side + part.rotation) % 4;
            well.spare = flow.rate - net.piped(part.x, part.y, liquid);
            if(well.spare > 0.5f) out.add(well);
        }
    }

    /**
     * Lays conduits for every liquid the given blocks need and do not get from each other, from what
     * the enemy has that makes it. Two turbines' water can be led to one drill; a second conduit may
     * join the first on the way.
     * @return false if a liquid could not be brought; {@link #lacking} then says which, if it is that there is too little of it
     * within reach, and is null if there is enough and only no way was found to lay the conduit
     */
    private boolean plumb(Project project, Seq<Part> parts){
        lacking = null;
        if(pipes.duct == null) return Plumbing.needs(parts).isEmpty();
        for(Plumbing.Tap need : Plumbing.needs(parts)){
            IntSet into = new IntSet();
            Shape.tiles(need.part, into);
            //most of what it needs will do: it runs, a little slower
            if(pipe(project, need.liquid, need.rate, into, need.part.x, need.part.y) > need.rate * 0.2f) return false;
        }
        return true;
    }

    /** How far a conduit may run to bring something its liquid. */
    private int reachOfPipes(){
        return needy ? Options.feedLimit.get() * 5 / 2 : Options.feedLimit.get();
    }

    /** Notes what a new layout makes in liquids, so that later things can draw on it. */
    private void springs(Project project, Seq<Part> parts){
        for(Plumbing.Tap spare : Plumbing.gives(parts)){
            project.things.add(new Thing(Net.Kind.spring, spare.part.x, spare.part.y, spare.liquid, spare.rate));
        }
    }

    /**
     * Whether units can get out of every unit block of a layout: the ground in front of each holds
     * the next block of the chain, which takes them, or is free for them to leave by.
     * @param roomy whether there has to be room besides for what may later be put in front of it to take its units:
     * as much ground as the block keeps clear for that, with nothing at all on it
     * @param bare whether the ground has to be empty, or only such that units can cross it: a duct is no bar to them
     */
    private boolean exitsClear(Layout layout, boolean roomy, boolean bare){
        IntSet own = new IntSet();
        for(Part part : layout.parts){
            if(part.block instanceof UnitBlock) Shape.tiles(part, own);
        }
        IntSeq ahead = new IntSeq();
        for(Part part : layout.parts){
            if(!(part.block instanceof UnitBlock)) continue;
            Shape.along(part, part.rotation, ahead);
            if(own.contains(ahead.get(0))) continue;
            front.clear();
            brain.frontOf(part.block, part.x, part.y, part.rotation, front);
            int rows = roomy ? front.size / part.block.size : 3;
            int dx = Geometry.d4x(part.rotation), dy = Geometry.d4y(part.rotation);
            for(int i = 0; i < ahead.size; i++){
                for(int step = 0; step < rows; step++){
                    int x = Point2.x(ahead.get(i)) + dx * step, y = Point2.y(ahead.get(i)) + dy * step;
                    if(bare ? !ground.open(x, y) : !ground.passable(x, y)) return false;
                }
            }
        }
        return true;
    }

    @SafeVarargs
    private static Seq<Seq<Part>> groups(Seq<Part>... groups){
        Seq<Seq<Part>> out = new Seq<>();
        for(Seq<Part> group : groups) out.add(group);
        return out;
    }

    private static float powerUse(Seq<Part> parts){
        float total = 0f;
        for(Part part : parts){
            if(part.block.consPower != null && !part.block.consPower.buffered) total += part.block.consPower.usage;
        }
        return total;
    }

    /** Whether the game allows every part of a project where it is planned. */
    private boolean buildable(Project project){
        for(Part part : project.parts){
            //where a wall comes down first the game will only allow it once that is done
            Building standing = world.build(part.x, part.y);
            if(standing != null && project.removals.contains(standing.pos())) continue;
            if(!Build.validPlace(part.block, ground.team, part.x, part.y, part.rotation)){
                refused = part.block.name + " at " + part.x + "," + part.y + (standing == null ? "" : ", where " + standing.block.name + " of " + standing.team.name + " stands");
                //on a map with fog that may only be that the ground has not been seen: something is sent to look at it
                if(fitter.unseen(part.x, part.y)){
                    refused += ", on ground it has not seen yet";
                    if(fitter.unseenAt == -1) fitter.unseenAt = Point2.pack(part.x, part.y);
                }
                return false;
            }
        }
        return true;
    }

    private String refused = "", feedNote = "", supplyNote = "";

    /**
     * Leads a line that stops short of a core on to one again. A line stops short where the player has
     * built across the way it was laid out along, or has put something of its own where a piece of it
     * stood. The new piece goes round whatever is in the way, or over it by bridge, and joins what is
     * left of the old line beyond if that still stands. The last ducts before the gap may be laid
     * afresh, so that the line can turn where it now runs up against something.
     * @param laid every tile the enemy has laid something out on
     * @return the piece, or null if no way was found
     */
    public @Nullable Project mend(Thing source, IntSet laid){
        if(source.item == null || late()) return null;
        IntSeq tiles = new IntSeq(net.trace(source.x, source.y));
        int endX = net.endX, endY = net.endY;
        note = "line for " + source.item.name + " from " + source.x + "," + source.y + ": cut at " + endX + "," + endY + ", and no way on from there";
        //with its first duct gone there is nothing to lead on from
        if(tiles.isEmpty()) return null;

        IntSet joins = joinable(source.rate, source.item);
        joins.addAll(leftOver(laid));
        for(int i = 0; i < tiles.size; i++) joins.remove(tiles.get(i));

        IntSet feeders = new IntSet();
        for(int back = 0; back <= 3 && back < tiles.size && !late(); back++){
            ground.relaid.clear();
            int startX = endX, startY = endY;
            if(back > 0){
                //only plain ducts that nothing else empties into are laid afresh
                boolean plain = true;
                for(int i = 1; i <= back && plain; i++){
                    int pos = tiles.get(tiles.size - i), before = tiles.get(tiles.size - i - 1);
                    feeders.clear();
                    feeders.add(before);
                    plain = ground.look(Point2.x(pos), Point2.y(pos)) && ground.block == lines.duct && !ground.fedByOthers(Point2.x(pos), Point2.y(pos), ground.rotation, feeders)
                        && !net.things.contains(thing -> thing.kind == Net.Kind.branch && thing.pos() == pos);
                    if(plain) ground.relaid.add(pos);
                }
                if(!plain) break;
                startX = Point2.x(tiles.get(tiles.size - back));
                startY = Point2.y(tiles.get(tiles.size - back));
            }
            feeders.clear();
            feeders.add(tiles.get(tiles.size - back - 1));
            Lines.Line line = lines.route(startX, startY, feeders, Lines.Goal.core(joins));
            if(line == null) continue;
            //what stands already just as the new piece has it stays as it is
            line.parts.removeAll(part -> world.build(part.x, part.y) != null && world.build(part.x, part.y).team == ground.team
                && world.build(part.x, part.y).block == part.block && world.build(part.x, part.y).rotation == part.rotation);
            if(line.parts.isEmpty()) continue;

            Project project = new Project("line for " + source.item.name + " led on");
            add(project, line.parts);
            project.removals.addAll(line.removals);
            ground.relaid.clear();
            if(!buildable(project)){
                drop(project, "the game does not allow " + refused);
                continue;
            }
            return project;
        }
        ground.relaid.clear();
        return null;
    }

    /** A stretch of line to be taken up once the line has been turned another way. */
    public static class Retire{
        public int x, y, rotation;
        public Block block;
        public final IntSeq tiles = new IntSeq();
        public float until;
    }

    /**
     * Leads the last stretch of a line home another way, where that is a good deal shorter than the
     * way it runs now. A line is laid by the best way there is at the time; later there are other
     * lines it could run into, and the last stretch of any line may take in whatever comes. The
     * stretch that can be moved begins past the last place something is led off the line and past the
     * last place another line runs into it: nothing depends on how it runs from there on.
     * @return the new way, with the old ducts it leaves noted to be taken up; or null if there is nothing to be gained
     */
    public @Nullable Project relead(Thing source){
        return relead(source, null, 0, 0);
    }

    /**
     * @param toward the tiles of a line that supplies something which is going short of the item: the stretch is then
     * led into that line instead of home, however long the way, so that what ran past to a core feeds the thing first
     */
    public @Nullable Project relead(Thing source, @Nullable IntSet toward, int towardX, int towardY){
        return relead(source, toward, towardX, towardY, null);
    }

    /**
     * @param body the tiles of something that takes the item and has no line of it at all: the stretch is then led into
     * that, and the cores get none of what came along it. For when they are well filled with it already
     */
    public @Nullable Project relead(Thing source, @Nullable IntSet toward, int towardX, int towardY, @Nullable IntSet body){
        if(source.item == null || late()) return null;
        if(body != null && toward == null) toward = new IntSet();
        IntSeq tiles = new IntSeq(net.trace(source.x, source.y));
        //a short line home has no shorter way to be led; led to something going short, a short one does as well as a long one
        //(the drills right by a core had the only graphite there was, and a furnace further out stood idle beside them)
        if(tiles.size < (toward == null ? 14 : 4) || !(ground.look(net.endX, net.endY) && ground.block instanceof mindustry.world.blocks.storage.CoreBlock)){
            note = "the line from " + source.x + "," + source.y + " is " + tiles.size + " long" + (ground.look(net.endX, net.endY) && ground.block instanceof mindustry.world.blocks.storage.CoreBlock ? "" : " and does not end in a core");
            return null;
        }

        //where the stretch that may be moved begins
        IntSet own = new IntSet();
        for(int i = 0; i < tiles.size; i++) own.add(tiles.get(i));
        int from = 1;
        for(int i = 0; i < tiles.size; i++){
            int pos = tiles.get(i);
            if(net.things.contains(thing -> thing.kind == Net.Kind.branch && thing.pos() == pos)) from = i + 1;
            //bridges and routers stay where they are: only plain duct is laid afresh
            if(!(ground.look(Point2.x(pos), Point2.y(pos)) && ground.block == lines.duct)) from = i + 1;
        }
        for(Thing other : net.things){
            if(other == source || (other.kind != Net.Kind.mine && other.kind != Net.Kind.maker)) continue;
            IntSeq theirs = net.trace(other.x, other.y);
            boolean inside = false;
            for(int i = 0; i < theirs.size; i++){
                boolean shared = own.contains(theirs.get(i));
                //where it comes onto this line from outside
                if(shared && !inside) from = Math.max(from, tiles.indexOf(theirs.get(i)) + 1);
                inside = shared;
            }
        }
        int old = tiles.size - from;
        if(old < (toward == null ? 12 : 3)){
            note = "the line from " + source.x + "," + source.y + " has only " + old + " of its " + tiles.size + " tiles past where others join it or it is led off";
            return null;
        }

        float rate = net.lines().get(tiles.get(from), source.rate);
        IntSet joins = joinable(rate, source.item);
        for(int i = 0; i < tiles.size; i++) joins.remove(tiles.get(i));
        IntSet feeders = new IntSet();
        feeders.add(tiles.get(from - 1));
        ground.relaid.clear();
        for(int i = from; i < tiles.size; i++) ground.relaid.add(tiles.get(i));
        Lines.Line line;
        try{
            if(body != null){
                line = lines.route(Point2.x(tiles.get(from)), Point2.y(tiles.get(from)), feeders, Lines.Goal.into(body, towardX, towardY, null));
            }else if(toward != null){
                IntSet there = new IntSet();
                IntSet.IntSetIterator each = toward.iterator();
                while(each.hasNext){
                    int pos = each.next();
                    if(joins.contains(pos)) there.add(pos);
                }
                line = there.isEmpty() ? null : lines.route(Point2.x(tiles.get(from)), Point2.y(tiles.get(from)), feeders, Lines.Goal.into(none, towardX, towardY, there));
            }else{
                line = lines.route(Point2.x(tiles.get(from)), Point2.y(tiles.get(from)), feeders, Lines.Goal.core(joins));
            }
        }finally{
            ground.relaid.clear();
        }
        //worth it only if it saves a good deal of duct: a third of the stretch, and a dozen tiles at the least. led to
        //something that is going short it is worth it whatever it takes, within what a branch may run to
        if(line == null || (toward == null ? old - line.parts.size < Math.max(12, old / 3) : line.parts.size > Options.feedLimit.get())){
            note = "the line from " + source.x + "," + source.y + ": " + (line == null ? "no way (looked at " + lines.lastLooked + ": built on " + lines.shutBy[1] + ", closed " + lines.shutBy[0] + ", pushed into " + lines.shutBy[2] + ", no bridge " + lines.shutBy[3] + ")" : "a way " + line.parts.size + " long");
            return null;
        }

        Project project = new Project("line for " + source.item.name + " led home a shorter way");
        IntSet kept = new IntSet();
        for(Part part : line.parts){
            int pos = Point2.pack(part.x, part.y);
            kept.add(pos);
            Building standing = world.build(part.x, part.y);
            boolean same = standing != null && standing.team == ground.team && standing.block == part.block && standing.rotation == part.rotation;
            if(same) continue;
            if(standing != null && standing.team == ground.team && own.contains(pos)){
                //a duct of the old way that the new way turns: put up last, so that the line runs as it did until the rest is there
                project.last.add(part);
                if(project.turn == null) project.turn = part;
            }else{
                project.parts.add(part);
            }
        }
        if(project.turn == null){
            note = "the line from " + source.x + "," + source.y + ": the new way does not turn off the old one";
            return null;
        }
        ground.plan(line.parts);
        project.removals.addAll(line.removals);
        for(int i = from; i < tiles.size; i++){
            if(!kept.contains(tiles.get(i))) project.retire.add(tiles.get(i));
        }
        if(!buildable(project)) return drop(project, "the game does not allow " + refused);
        return project;
    }

    /**
     * Brings an item to something that stands and is going short of it, by a second line led off
     * wherever enough of it goes by now. The line it was built with may have been cut, or what that
     * line was led off may have run dry since: more of the item being made somewhere else does not
     * help a yard whose own supply is gone.
     */
    public @Nullable Project refeed(Building hungry, Item item, float rate){
        if(late()) return null;
        note = "line for " + item.name + " to the " + hungry.block.name + " at " + hungry.tileX() + "," + hungry.tileY() + ": nowhere to lead it off from";
        int size = hungry.block.size, offset = -(size - 1) / 2, x0 = hungry.tileX() + offset, y0 = hungry.tileY() + offset;
        //every open tile at its sides, with a duct on it that faces in
        Seq<Port> ports = new Seq<>();
        for(int i = 0; i < size; i++){
            for(int side = 0; side < 4; side++){
                int x = side == 0 ? x0 + size : side == 2 ? x0 - 1 : x0 + i, y = side == 1 ? y0 + size : side == 3 ? y0 - 1 : y0 + i;
                if(ground.open(x, y)) ports.add(new Port(Role.in, x, y, (side + 2) % 4, item));
            }
        }
        //a line that carries it may run right past: then it is fed out of that, and nothing has to be led anywhere
        IntSet body = new IntSet();
        for(int dx = 0; dx < size; dx++){
            for(int dy = 0; dy < size; dy++) body.add(Point2.pack(x0 + dx, y0 + dy));
        }
        IntFloatMap carried = net.carried(item);
        IntSet seen = brain.seen(item);
        for(int i = 0; i < size; i++){
            for(int side = 0; side < 4; side++){
                int x = side == 0 ? x0 + size : side == 2 ? x0 - 1 : x0 + i, y = side == 1 ? y0 + size : side == 3 ? y0 - 1 : y0 + i, pos = Point2.pack(x, y);
                if((carried.get(pos, 0f) < rate * enough && !seen.contains(pos)) || !pure(pos, item)) continue;
                Part head = lines.dock(x, y, body);
                if(head == null) continue;
                Project project = new Project("way in for " + item.name + " to " + hungry.block.name);
                project.last.add(head);
                ground.plan(head);
                project.things.add(new Thing(Net.Kind.branch, x, y, item, rate));
                if(buildable(project)) return project;
                drop(project, "the game does not allow " + refused);
            }
        }
        ports.sort(port -> brain.toLine(item, port.x, port.y));
        for(int i = 0; i < Math.min(ports.size, 3) && !late(); i++){
            Project project = new Project("line for " + item.name + " to " + hungry.block.name);
            if(!feed(project, ports.get(i), rate)){
                drop(project, "no line with enough " + item.name + " to lead it off from [" + feedNote + "]");
                continue;
            }
            if(project.parts.isEmpty() || !buildable(project)){
                drop(project, "the game does not allow " + refused);
                continue;
            }
            return project;
        }
        return null;
    }

    /**
     * What is left of lines the enemy laid that no longer begin anywhere: ducts of its own that still
     * run on to a core. A line that was cut is led back into the rest of itself.
     */
    private IntSet leftOver(IntSet laid){
        IntSet out = new IntSet(), dead = new IntSet();
        IntFloatMap known = net.lines();
        IntSeq starts = new IntSeq();
        for(Building build : ground.team.data().buildings){
            if(build.block == lines.duct) starts.add(build.pos());
        }
        for(var plan : ground.team.data().plans){
            if(plan.block == lines.duct) starts.add(Point2.pack(plan.x, plan.y));
        }
        for(int s = 0; s < starts.size; s++){
            int pos = starts.get(s);
            if(known.containsKey(pos) || !laid.contains(pos) || out.contains(pos) || dead.contains(pos)) continue;
            IntSeq way = net.trace(Point2.x(pos), Point2.y(pos));
            boolean home = ground.look(net.endX, net.endY) && ground.block instanceof mindustry.world.blocks.storage.CoreBlock;
            for(int i = 0; i < way.size; i++){
                if(!known.containsKey(way.get(i))) (home ? out : dead).add(way.get(i));
            }
        }
        return out;
    }

    //endregion
    //region projects

    /** A row of drills on an ore, with its line and its power. */
    public @Nullable Project mine(Blueprint blueprint, Item item, int want, int reach){
        BeamDrill drill = (BeamDrill)blueprint.main();
        if(late()) return null;
        fitter.skip.clear();
        note = "mine for " + item.name + ": no wall with it in reach";

        for(int attempt = 0; attempt < 4 && !late(); attempt++){
            Layout layout = fitter.row(blueprint, item, want, reach);
            if(layout == null) return null;
            fitter.skip.add(Point2.pack(layout.main().x, layout.main().y));
            //if this place comes to nothing it is left alone for a while, so that the next look goes somewhere else
            brain.shun(layout.main().x, layout.main().y);
            if(!brain.reachable(layout.main().block, layout.main().x, layout.main().y)){
                note = "mine for " + item.name + ": the player is too near the place";
                continue;
            }

            Project project = new Project("mine for " + item.name);
            lastTried = layout;
            //counted before the layout is planned: its own blocks do not stand in the way of its beams
            int beams = 0;
            for(Part part : layout.parts){
                if(part.block instanceof BeamDrill) beams += fitter.beams(drill, part.x, part.y, part.rotation, item);
            }
            add(project, layout);
            if(!lead(project, layout, Net.Kind.mine, item, beams * Rates.beam(drill, item))){
                drop(project, "no way to lay a line from " + layout.main().x + "," + layout.main().y + " to a core (looked at " + lines.lastLooked + " places)");
                continue;
            }
            if(!plumb(project, layout.parts)){
                if(lacking == null){
                    drop(project, "no way to lay a conduit to " + layout.main().x + "," + layout.main().y);
                    continue;
                }
                //the place is good; what the drills run on can be built, and then this is taken up again
                brain.unshun(layout.main().x, layout.main().y);
                return drop(project, "no " + lacking.name + " to be had near " + layout.main().x + "," + layout.main().y);
            }
            if(!energize(project, groups(layout.parts), powerUse(layout.parts))){
                drop(project, "no power to be had at " + layout.main().x + "," + layout.main().y);
                continue;
            }
            if(!buildable(project)){
                drop(project, "the game does not allow " + refused);
                continue;
            }
            brain.unshun(layout.main().x, layout.main().y);
            Port out = layout.port(Role.out, null);
            project.assets.add(new Asset(Net.Kind.mine, layout, out.x, out.y));
            return project;
        }
        return null;
    }

    /** A drill standing on ore in the floor, with its line, its water and its power. */
    /**
     * Whether what is being worked out is something the enemy can't do without and has none of yet. For
     * that it settles for a poorer place and a longer way: a thin patch of ore, water from far off.
     */
    public boolean needy;

    public @Nullable Project pad(Blueprint blueprint, Item item, int reach){
        //the ore it keeps everything else off is what this is for
        ground.drilling = true;
        try{
            return drillOn(blueprint, item, reach);
        }finally{
            ground.drilling = false;
        }
    }

    private @Nullable Project drillOn(Blueprint blueprint, Item item, int reach){
        Drill drill = (Drill)blueprint.main();
        if(late()) return null;
        //places that came to nothing lately are passed over, each by itself: a drill is several tiles across, and to shut
        //the one tile to everything would shut every place for a drill that touches it
        fitter.skip.clear();
        for(var entry : padTried){
            if(entry.value > brain.now()) fitter.skip.add(entry.key);
        }
        note = "drill for " + item.name + ": no ore in the floor in reach";

        for(int attempt = 0; attempt < 3 && !late(); attempt++){
            Layout layout = fitter.pad(blueprint, item, reach, needy ? 0.25f : 0.5f);
            if(layout == null){
                if(attempt == 0) note = "drill for " + item.name + ": no place for it [" + fitter.padNote + "]";
                return null;
            }
            int x = layout.main().x, y = layout.main().y;
            fitter.skip.add(Point2.pack(x, y));
            padTried.put(Point2.pack(x, y), brain.now() + 60f * 60f * 4f);
            if(!brain.reachable(drill, x, y)){
                note = "drill for " + item.name + ": the player is too near the place";
                continue;
            }

            Project project = new Project("drill for " + item.name);
            lastTried = layout;
            add(project, layout);
            ground.homing = true;
            boolean led;
            try{
                led = lead(project, layout, Net.Kind.mine, item, Rates.drill(drill, item, fitter.lastOre));
            }finally{
                ground.homing = false;
            }
            if(!led && !needy){
                drop(project, "no way to lay a line from " + x + "," + y + " to a core");
                continue;
            }
            //a first source of something it can't do without is worth having without a line: what piles up in the drill its
            //builders carry home, as a player would by hand, and a line is laid when a way is found. it is noted at the drill itself
            if(!led) project.things.add(new Thing(Net.Kind.mine, x, y, item, Rates.drill(drill, item, fitter.lastOre)));
            if(!plumb(project, layout.parts)){
                if(lacking == null){
                    drop(project, "no way to lay a conduit to " + x + "," + y);
                    continue;
                }
                //the place is good; what it lacks can be built, and then this is taken up again
                padTried.remove(Point2.pack(x, y), 0f);
                return drop(project, "no " + lacking.name + " to be had near " + x + "," + y);
            }
            if(!energize(project, groups(layout.parts), powerUse(layout.parts))){
                drop(project, "no power to be had at " + x + "," + y);
                continue;
            }
            if(!buildable(project)){
                drop(project, "the game does not allow " + refused);
                continue;
            }
            padTried.remove(Point2.pack(x, y), 0f);
            Port out = layout.port(Role.out, null);
            project.assets.add(new Asset(Net.Kind.mine, layout, led ? out.x : x, led ? out.y : y));
            return project;
        }
        return null;
    }

    /**
     * A line to a core for a drill that was put up without one, because no way could be found for it
     * at the time. The note of the drill moves to where its line begins.
     */
    public @Nullable Project connect(Thing loose, Layout layout){
        if(late() || loose.item == null) return null;
        note = "line for " + loose.item.name + " from the drill at " + loose.x + "," + loose.y + ": still no way to a core";
        Project project = new Project("line for " + loose.item.name);
        ground.drilling = true;
        ground.homing = true;
        try{
            //from where the blueprint has its ore leave, or failing that from any free tile at its side: a drill hands
            //its ore to whatever stands against it, and what was built round it since may have shut the one place in
            Part drill = layout.main();
            IntSet own = footprint(layout.parts);
            IntSet joins = joinable(loose.rate, loose.item);
            Seq<int[]> starts = new Seq<>();
            Port out = layout.port(Role.out, null);
            if(out != null && ground.open(out.x, out.y)) starts.add(new int[]{out.x, out.y});
            int offset = -(drill.block.size - 1) / 2, size = drill.block.size;
            Seq<int[]> sides = new Seq<>();
            for(int i = 0; i < size; i++){
                for(int side = 0; side < 4; side++){
                    int x = drill.x + offset + (side == 0 ? size : side == 2 ? -1 : i), y = drill.y + offset + (side == 1 ? size : side == 3 ? -1 : i);
                    if(ground.open(x, y)) sides.add(new int[]{x, y});
                }
            }
            sides.sort(at -> territory.near(at[0], at[1]));
            starts.addAll(sides);

            int looked = 0;
            Lines.Line line = null;
            int[] from = null;
            for(int i = 0; i < Math.min(starts.size, 6) && line == null && !late(); i++){
                from = starts.get(i);
                line = routeHome(from[0], from[1], own, joins);
                looked += lines.lastLooked;
            }
            if(line == null) return drop(project, "still no way to a core from " + loose.x + "," + loose.y + " (" + Math.min(starts.size, 6) + " ways out tried, looked at " + looked + " places)");
            add(project, line.parts);
            project.removals.addAll(line.removals);
            project.things.add(new Thing(Net.Kind.mine, from[0], from[1], loose.item, loose.rate));
        }finally{
            ground.drilling = false;
            ground.homing = false;
        }
        if(!buildable(project)) return drop(project, "the game does not allow " + refused);
        project.forget.add(loose);
        return project;
    }

    private final IntFloatMap padTried = new IntFloatMap();

    private static boolean onVent(Block block){
        return block instanceof mindustry.world.blocks.power.ThermalGenerator
            || (block instanceof AttributeCrafter crafter && crafter.attribute == mindustry.world.meta.Attribute.steam);
    }

    /** The block of a blueprint that has to stand on a vent, if it has one. */
    public static @Nullable Block ventBlock(Blueprint blueprint){
        Part part = blueprint.parts.find(p -> onVent(p.block));
        return part == null ? null : part.block;
    }

    /**
     * Something that makes a liquid, on a vent near where the liquid is wanted: a condenser for water,
     * a condenser with an electrolyzer for the two gases.
     */
    public @Nullable Project spring(Blueprint blueprint, int nearX, int nearY){
        if(late()) return null;
        note = blueprint.name + ": no free vent near " + nearX + "," + nearY;
        //the block of it that has to stand on a vent
        int ventIndex = blueprint.parts.indexOf(part -> onVent(part.block));
        if(ventIndex == -1) return springNear(blueprint, nearX, nearY);
        Block onVent = blueprint.parts.get(ventIndex).block;

        IntSeq vents = brain.terrain.vents(onVent, ground.team);
        Seq<int[]> near = new Seq<>();
        int limit = reachOfPipes();
        for(int i = 0; i < vents.size; i++){
            int x = Point2.x(vents.get(i)), y = Point2.y(vents.get(i));
            if(Math.abs(x - nearX) + Math.abs(y - nearY) <= limit && ground.clear(onVent, x, y)) near.add(new int[]{x, y});
        }
        near.sort(at -> Math.abs(at[0] - nearX) + Math.abs(at[1] - nearY));

        int tries = 0;
        for(int[] vent : near){
            if(++tries > 6 || late()) break;
            if(!brain.reachable(onVent, vent[0], vent[1])) continue;
            for(int way = 0; way < 8; way++){
                //turned each way in turn, and moved so that the block that needs the vent stands on it
                Layout probe = blueprint.at(0, 0, way % 4, way >= 4, 1);
                Part anchor = probe.parts.get(ventIndex);
                Layout layout = blueprint.at(vent[0] - anchor.x, vent[1] - anchor.y, way % 4, way >= 4, 1);
                if(!clear(layout) || !fitter.valid(layout)) continue;

                Project project = new Project(blueprint.name);
                lastTried = layout;
                add(project, layout);
                if(plumb(project, layout.parts) && energize(project, groups(layout.parts), powerUse(layout.parts)) && buildable(project)){
                    springs(project, layout.parts);
                    project.assets.add(new Asset(Net.Kind.spring, layout, layout.main().x, layout.main().y));
                    return project;
                }
                note = blueprint.name + ": no power to be had at " + vent[0] + "," + vent[1];
                brain.syncGround();
            }
        }
        return null;
    }

    /**
     * A furnace or other factory with the crushers that feed it sand: the crushers against a wall, the
     * factory close by, its other inputs branched off the enemy's lines, and what it makes led to a core.
     */
    /**
     * More crushers for a factory that stands and is short of what they make (a furnace short of sand): a row on
     * the nearest wall that yields it, its line run onto the line that already brings it to the factory, or else
     * into the factory itself. What they make is never led to a core: nothing is built of it.
     * @param feed the tiles of the line that brings it to the factory now, if any
     */
    public @Nullable Project quarry(Blueprint crushers, int want, int reach, Building factory, @Nullable IntSet feed){
        WallCrafter crusher = (WallCrafter)crushers.main();
        note = "crushers for the " + factory.block.name + " at " + factory.tileX() + "," + factory.tileY() + ": no wall that yields " + crusher.output.name + " in reach";
        if(late()) return null;
        IntSet body = new IntSet();
        factory.tile.getLinkedTiles(tile -> body.add(tile.pos()));
        fitter.skip.clear();
        for(int attempt = 0; attempt < 3 && !late(); attempt++){
            fitter.order = (x, y) -> Math.abs(x - factory.tileX()) + Math.abs(y - factory.tileY());
            Layout row;
            try{
                row = fitter.row(crushers, null, want, reach);
            }finally{
                fitter.order = null;
            }
            if(row == null) return null;
            fitter.skip.add(Point2.pack(row.main().x, row.main().y));
            if(!brain.reachable(row.main().block, row.main().x, row.main().y)) continue;
            Project project = new Project("crushers for " + factory.block.name);
            add(project, row);
            Port from = row.port(Role.out, null);
            if(from == null) return null;
            IntSet rowTiles = footprint(row.parts);
            Lines.Line line = null;
            if(feed != null && !feed.isEmpty()){
                IntSet joins = joinable(0.1f, crusher.output), there = new IntSet();
                IntSet.IntSetIterator each = feed.iterator();
                while(each.hasNext){
                    int pos = each.next();
                    if(joins.contains(pos)) there.add(pos);
                }
                if(!there.isEmpty()) line = lines.route(from.x, from.y, rowTiles, Lines.Goal.into(none, factory.tileX(), factory.tileY(), there));
            }
            if(line == null) line = lines.route(from.x, from.y, rowTiles, Lines.Goal.into(body, factory.tileX(), factory.tileY(), null));
            if(line == null){
                drop(project, "no way to lead what they make to the " + factory.block.name);
                continue;
            }
            add(project, line.parts);
            project.removals.addAll(line.removals);
            if(!energize(project, groups(row.parts), powerUse(row.parts))){
                drop(project, "no power to be had for them");
                continue;
            }
            float made = 0f;
            for(Part part : row.parts){
                if(!(part.block instanceof WallCrafter)) continue;
                float worth = 0f;
                for(int lane = 0; lane < crusher.size; lane++){
                    crusher.nearbySide(part.x, part.y, part.rotation, lane, Tmp.p1);
                    worth += world.tile(Tmp.p1.x, Tmp.p1.y).block().attributes.get(crusher.attribute);
                }
                made += Rates.crusher(crusher, worth);
            }
            project.things.add(new Thing(Net.Kind.mine, from.x, from.y, crusher.output, made));
            if(buildable(project)) return project;
            drop(project, "the game does not allow " + refused);
        }
        return null;
    }

    /**
     * Two mass drivers, the first linked to the second, each with power where it can be had: the first in front of a yard on
     * its own ground, the second where the units it carries are to land.
     */
    public @Nullable Project ferry(Block driver, int ax, int ay, int arot, int bx, int by, int brot){
        if(late()) return null;
        Project project = new Project("mass drivers to carry units across");
        Part sender = new Part(driver, ax, ay, arot, Point2.pack(bx, by)), receiver = new Part(driver, bx, by, brot, null);
        add(project, Seq.with(sender));
        add(project, Seq.with(receiver));
        //each with a node at its side for the power to come in by: a grid is only ever joined through a node
        Part senderNode = nodeBeside(sender, arot), receiverNode = nodeBeside(receiver, brot);
        if(senderNode == null) return drop(project, "no room for a node by the mass driver at " + ax + "," + ay);
        add(project, Seq.with(senderNode));
        if(receiverNode != null) add(project, Seq.with(receiverNode));
        //the only way its army has to the other side: worth a place on a grid that is loaded already
        boolean before = firstOfKind;
        firstOfKind = true;
        try{
            if(!energize(project, groups(Seq.with(sender, senderNode)), powerUse(Seq.with(sender)))){
                return drop(project, "no power to be had for the mass driver at " + ax + "," + ay);
            }
            //the far one may stand where no grid reaches: it is put up all the same
            if(receiverNode != null) energize(project, groups(Seq.with(receiver, receiverNode)), powerUse(Seq.with(receiver)));
        }finally{
            firstOfKind = before;
        }
        if(!buildable(project)) return drop(project, "the game does not allow " + refused);
        return project;
    }

    /** A power node on open ground right beside a block, off its front and back, or null with no room. */
    private @Nullable Part nodeBeside(Part block, int rotation){
        if(power.node == null) return null;
        int half = block.block.size / 2;
        for(int side = 0; side < 4; side++){
            if(side == rotation || side == (rotation + 2) % 4) continue;
            for(int slide = -half; slide <= half; slide++){
                int x = block.x + Geometry.d4x(side) * (half + 1) + Geometry.d4y(side) * slide, y = block.y + Geometry.d4y(side) * (half + 1) + Geometry.d4x(side) * slide;
                if(ground.fits(power.node, x, y, 0)) return new Part(power.node, x, y, 0);
            }
        }
        return null;
    }

    /** How far from home the crushers of a plant are looked for first. */
    static final int plantNear = 40;

    public @Nullable Project plant(Blueprint crushers, Blueprint factory, int want, int reach){
        GenericCrafter crafter = (GenericCrafter)factory.main();
        WallCrafter crusher = (WallCrafter)crushers.main();
        Item made = crafter.outputItems[0].item;
        if(late()) return null;
        fitter.skip.clear();
        note = "plant for " + made.name + ": no wall that yields sand in reach";

        //the crushers go where the factory's other supplies pass close by: sand is to be had almost anywhere, and a furnace
        //put down far from the graphite, with other lines in between, can't be reached with it
        Item other = null;
        for(Port port : factory.ports){
            if(port.role == Role.in && port.item != crusher.output && other == null) other = port.item;
        }
        Item beside = other;
        for(int attempt = 0; attempt < 3 && !late(); attempt++){
            fitter.order = beside == null ? null : (x, y) -> Math.min(brain.toLine(beside, x, y), 100000) * 3 + Math.min(territory.near(x, y), 100000) / 4;
            Layout row;
            boolean far = fitter.farOk;
            try{
                //near home first: a longer row of crushers further out wins on how many it holds, and the furnace by it is
                //then out of reach of everything it takes. (with time to look that far, a sector's first furnace waited
                //minutes for a place by a long wall forty tiles out)
                fitter.farOk = false;
                row = fitter.row(crushers, null, want, Math.min(reach, plantNear));
                fitter.farOk = far;
                if(row == null) row = fitter.row(crushers, null, want, reach);
            }finally{
                fitter.order = null;
                fitter.farOk = far;
            }
            if(row == null) return null;
            fitter.skip.add(Point2.pack(row.main().x, row.main().y));
            brain.shun(row.main().x, row.main().y);
            if(!brain.reachable(row.main().block, row.main().x, row.main().y)) continue;

            Project project = new Project("plant for " + made.name);
            add(project, row);
            Port from = row.port(Role.out, null);
            IntSet rowTiles = footprint(row.parts);

            //what the crushers bring in a second, at this wall
            float sand = 0f;
            for(Part part : row.parts){
                if(!(part.block instanceof WallCrafter)) continue;
                float worth = 0f;
                for(int lane = 0; lane < crusher.size; lane++){
                    crusher.nearbySide(part.x, part.y, part.rotation, lane, Tmp.p1);
                    worth += world.tile(Tmp.p1.x, Tmp.p1.y).block().attributes.get(crusher.attribute);
                }
                sand += Rates.crusher(crusher, worth);
            }
            float share = Mathf.clamp(sand / Math.max(Rates.uses(crafter, crusher.output), 0.001f));

            Layout placed = null;
            int tries = 0;
            String why = "";
            search:
            for(int radius = 3; radius <= 9; radius++){
                for(int dx = -radius; dx <= radius; dx++){
                    for(int dy = -radius; dy <= radius; dy++){
                        if(Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;
                        int x = from.x + dx, y = from.y + dy;
                        if(!ground.clear(crafter, x, y)) continue;
                        if(late()) break search;
                        for(int way = 0; way < 8; way++){
                            Layout layout = factory.at(x, y, way % 4, way >= 4, 1);
                            if(!clear(layout) || !fitter.valid(layout)) continue;
                            if(++tries > 10 || late()) break search;

                            int before = project.parts.size, lastBefore = project.last.size, thingsBefore = project.things.size;
                            add(project, layout);
                            boolean supplied = supply(project, layout, row, from, rowTiles, crusher.output, share);
                            if(supplied && lead(project, layout, Net.Kind.maker, made, Rates.makes(crafter, made) * share)){
                                placed = layout;
                                break search;
                            }
                            why = supplied ? "no way to a core for what it makes from " + x + "," + y : supplyNote;
                            //this place won't do: take back what was planned for it
                            brain.shun(x, y);
                            Seq<Part> undone = new Seq<>();
                            while(project.parts.size > before) undone.add(project.parts.pop());
                            ground.unplan(undone);
                            project.last.truncate(lastBefore);
                            project.things.truncate(thingsBefore);
                            //a duct that was to become a router is a duct again
                            brain.syncGround();
                            ground.plan(project.parts);
                        }
                    }
                }
            }
            if(placed == null){
                drop(project, "no place near the crushers at " + row.main().x + "," + row.main().y + " where its lines can be laid" + (tries == 0 ? " [no room for the factory]" : " [" + tries + " tried; the last: " + why + "]"));
                continue;
            }
            project.things.add(new Thing(Net.Kind.quarry, row.main().x, row.main().y, crusher.output, sand));

            if(!energize(project, groups(row.parts, placed.parts), powerUse(row.parts) + powerUse(placed.parts))){
                drop(project, "no power to be had at " + placed.main().x + "," + placed.main().y);
                continue;
            }
            if(!buildable(project)){
                drop(project, "the game does not allow " + refused);
                continue;
            }
            brain.unshun(row.main().x, row.main().y);
            project.assets.add(new Asset(Net.Kind.quarry, row, row.main().x, row.main().y));
            Port out = placed.port(Role.out, null);
            project.assets.add(new Asset(Net.Kind.maker, placed, out.x, out.y));
            return project;
        }
        return null;
    }

    /** Lays the lines into a factory: sand straight from the crushers, everything else off the enemy's own lines. */
    private boolean supply(Project project, Layout layout, Layout row, Port from, IntSet rowTiles, Item sand, float share){
        Port sandIn = layout.port(Role.in, sand);
        if(sandIn == null) return false;
        holdPorts(layout, sandIn, true);
        Lines.Line line = lines.route(from.x, from.y, rowTiles, Lines.Goal.port(sandIn));
        holdPorts(layout, sandIn, false);
        if(line == null || line.parts.size > 30){
            supplyNote = "no way for the sand to " + sandIn.x + "," + sandIn.y + (line == null ? " (looked at " + lines.lastLooked + ")" : " in 30 ducts");
            return false;
        }
        add(project, line.parts);

        for(Port port : layout.ports){
            if(port.role != Role.in || port.item == sand) continue;
            holdPorts(layout, port, true);
            boolean fed = feed(project, port, Rates.uses(layout.main().block, port.item) * share);
            holdPorts(layout, port, false);
            if(!fed){
                supplyNote = "no " + port.item.name + " to be led to " + port.x + "," + port.y + " [" + feedNote + "]";
                return false;
            }
        }
        return true;
    }

    /**
     * Keeps the places where a layout's other lines will come in and go out clear while one of them is
     * laid, so that the first does not run across where the second has to end.
     */
    private void holdPorts(Layout layout, @Nullable Port except, boolean hold){
        for(Port port : layout.ports){
            if(port == except) continue;
            int pos = Point2.pack(port.x, port.y);
            if(hold) ground.kept.add(pos); else ground.kept.remove(pos);
        }
    }

    /**
     * Leaves out of a layout the parts it can do without that there is no room for, or that the enemy
     * may not build: walls go up where they fit, and where they would stand in the way of its own
     * army, or on rock, they are simply not there.
     */
    private Layout trim(Layout layout){
        if(!layout.parts.contains(part -> part.optional)) return layout;
        //what it can't spare the stuff for is put up in the best it can: a wall of something it has plenty of
        for(int i = 0; i < layout.parts.size; i++){
            Part part = layout.parts.get(i);
            Block instead = part.optional ? brain.spared(part.block) : part.block;
            if(instead == part.block || instead == null) continue;
            Part other = new Part(instead, part.x, part.y, part.rotation, part.config);
            other.optional = true;
            layout.parts.set(i, other);
        }
        layout.parts.removeAll(part -> part.optional && (brain.spared(part.block) == null || !ground.fits(part.block, part.x, part.y, part.rotation)));
        return layout;
    }

    private boolean clear(Layout layout){
        return clear(layout, false);
    }

    /** @param against whether it is to stand against lines and be fed out of them: then where its inputs would be led in to does not matter */
    private boolean clear(Layout layout, boolean against){
        for(Part part : layout.parts){
            if(!ground.clear(part.block, part.x, part.y)) return false;
        }
        if(layout.ports.isEmpty() || against) return true;
        IntSet own = footprint(layout.parts);
        for(Port port : layout.ports){
            if(!ground.open(port.x, port.y)) return false;
            //a line has to be able to get to it, from one side or from behind for an input, and away from it for an output
            if(lines.pocket(port.x, port.y, own, port.role == Role.in ? port.rotation : -1)) return false;
        }
        return true;
    }

    /**
     * Something that is fed by lines and stands where it is wanted: a unit yard near home, a turret on
     * the way to the player. Every input is branched off a line that carries what it takes.
     * @param blueprints ways of laying the same thing out, the one liked best first
     * @param spots places to try, best first: x, y
     */
    public @Nullable Project fed(Seq<Blueprint> blueprints, Seq<int[]> spots, Seq<CoreBuild> targets, Net.Kind kind){
        Blueprint blueprint = blueprints.first();
        Block main = blueprint.main();
        lastSpots = spots;
        firstOfKind = kind == Net.Kind.yard ? net.count(Net.Kind.yard, null) == 0
            : kind == Net.Kind.maker && main instanceof GenericCrafter first && first.outputItems != null && net.count(Net.Kind.maker, first.outputItems[0].item) == 0;
        try{
            return feedAndPlace(blueprints, spots, targets, kind);
        }finally{
            firstOfKind = false;
        }
    }

    private @Nullable Project feedAndPlace(Seq<Blueprint> blueprints, Seq<int[]> spots, Seq<CoreBuild> targets, Net.Kind kind){
        Blueprint blueprint = blueprints.first();
        Block main = blueprint.main();
        int tries = 0, noRoom = 0, playerNear = 0, wontFit = 0, docked = 0, invalid = 0, shut = 0;
        String failed = null;

        //right against a line that carries what it takes comes before anywhere a line would have to be led to: those
        //places are tried just as they are, the rest are looked round
        Seq<int[]> places = docks(blueprint, spots);
        int exact = places.size;
        places.addAll(spots);

        //a unit yard goes by choice where there is room in front of it for what may later be added to it; the places are
        //gone over a second time for one where its units can merely get out
        for(int pass = kind == Net.Kind.yard ? 0 : 1; pass < 2 && !late(); pass++){
        boolean roomy = pass == 0;
        tries = docked = 0;
        search:
        for(int at = 0; at < places.size; at++){
            int[] spot = places.get(at);
            boolean against = at < exact;
            if(against && docked >= 16) continue;
            //a few tries for each place: if they come to nothing there, the trouble is with the place, and the next may do better
            int here = 0;
            around:
            for(int radius = 0; radius <= (against ? 0 : 10); radius++){
                for(int dx = -radius; dx <= radius; dx++){
                    for(int dy = -radius; dy <= radius; dy++){
                        if(Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;
                        if(here >= 3) break around;
                        int x = spot[0] + dx, y = spot[1] + dy;
                        if(!ground.clear(main, x, y)){
                            noRoom++;
                            continue;
                        }
                        //looking a place over takes time too
                        if(late()) break search;
                        if(!brain.reachable(main, x, y)){
                            playerNear++;
                            continue;
                        }

                        //facing the player; a factory may stand any way round
                        //with no core of the other side's on the map, as where it only has waves to hold out against, away from its own
                        CoreBuild target = Geometry.findClosest(x * tilesize, y * tilesize, targets), own = target != null ? null : ground.team.core();
                        int turns = target != null ? Mathf.mod(Mathf.round(Angles.angle(x * tilesize, y * tilesize, target.x, target.y) / 90f), 4)
                            : own != null ? Mathf.mod(Mathf.round(Angles.angle(own.x, own.y, x * tilesize, y * tilesize) / 90f), 4) : 0;
                        //a yard faces the other side by choice, and any way round where there is no room for that: its units find their way out
                        int each = kind == Net.Kind.maker || kind == Net.Kind.yard ? 8 : 2;
                        for(int way = 0; way < blueprints.size * each; way++){
                            blueprint = blueprints.get(way / each);
                            Layout layout = trim(blueprint.at(x, y, turns + way % each / 2, way % 2 == 1, 1));
                            if(!clear(layout, against)){
                                wontFit++;
                                continue;
                            }
                            if(!fitter.valid(layout)){
                                wontFit++;
                                invalid++;
                                continue;
                            }
                            //the ground in front kept bare only where it is to take an addition later: units walk over ducts
                            if(!exitsClear(layout, roomy, roomy)){
                                wontFit++;
                                shut++;
                                continue;
                            }
                            if((against ? ++docked > 16 : ++tries > 12) || late()) break search;
                            here++;

                            Project project = new Project(blueprint.name);
                            lastTried = layout;
                            add(project, layout);
                            boolean supplied = true;
                            Item unfed = null;
                            for(Port port : layout.ports){
                                if(port.role != Role.in) continue;
                                float rate = brain.feedRate(port.item, wanted(taker(layout, port, main), port.item));
                                holdPorts(layout, port, true);
                                boolean fed = dock(project, layout, port, rate) || feed(project, port, rate);
                                holdPorts(layout, port, false);
                                //a yard that gets the rest of what it takes may go up short of one thing, where that can be got
                                //anew: a source of it is then laid with its line running into the yard alone. through a base
                                //grown thick with lines that is often the only way a second line gets to it
                                if(!fed && kind == Net.Kind.yard && unfed == null && layout.ports.count(other -> other.role == Role.in) > 1 && brain.sourceable(port.item)){
                                    unfed = port.item;
                                    fed = true;
                                }
                                if(!fed){
                                    supplied = false;
                                    if(failed == null && onFeedFailed != null){
                                        holdPorts(layout, port, true);
                                        onFeedFailed.get(layout);
                                        holdPorts(layout, port, false);
                                    }
                                    if(failed == null) failed = "no line to branch " + port.item.name + " off within reach of " + x + "," + y + " [" + feedNote + "]";
                                    break;
                                }
                            }
                            if(supplied && !plumb(project, layout.parts)){
                                supplied = false;
                                if(lacking != null){
                                    failed = "no " + lacking.name + " to be had near " + x + "," + y;
                                    //the place is good; what it lacks can be built, and then this is taken up again
                                    brain.syncGround();
                                    break search;
                                }
                                if(failed == null) failed = "no way to lay a conduit to " + x + "," + y;
                            }
                            if(supplied && !energize(project, groups(layout.parts), powerUse(layout.parts))){
                                supplied = false;
                                failed = "no power to be had at " + x + "," + y;
                            }
                            if(supplied && kind == Net.Kind.maker){
                                //what it makes goes to a core, and from then on counts as a line that carries it
                                Item made = ((GenericCrafter)main).outputItems[0].item;
                                float share = 1f;
                                if(!lead(project, layout, kind, made, Rates.makes(main, made) * share)){
                                    supplied = false;
                                    if(failed == null) failed = "no way to lay a line from " + x + "," + y + " to a core";
                                }
                            }
                            //short of everything it takes is no yard at all
                            Item missing = unfed;
                            if(missing != null && !layout.ports.contains(other -> other.role == Role.in && other.item != missing)) supplied = false;
                            if(supplied && buildable(project)){
                                if(kind != Net.Kind.maker) project.things.add(new Thing(kind, x, y, (Item)null, 0f));
                                springs(project, layout.parts);
                                Port out = kind == Net.Kind.maker ? layout.port(Role.out, null) : null;
                                project.assets.add(new Asset(kind, layout, out != null ? out.x : x, out != null ? out.y : y));
                                return project;
                            }
                            //not here: the next look, now or when this is taken up again, goes somewhere else
                            brain.shun(x, y);
                            brain.syncGround();
                        }
                    }
                }
            }
        }
        }
        note = blueprints.first().name + ": " + (failed != null ? failed : spots.isEmpty() ? "its lines carry what it takes nowhere it could go"
            : "nowhere to put it [" + spots.size + " places looked round and " + exact + " against lines" + where(spots) + "; no room " + noRoom + ", player near " + playerNear + ", rest won't fit " + wontFit + " (" + invalid + " refused, " + shut + " with no way out)]");
        return null;
    }

    /**
     * Places where a block could stand right against lines that carry what it is fed with, so that it
     * can be fed out of them with no branch. Each line carries one thing: a block fed with two stands
     * where one line runs along one side of it and the other along another, as happens where lines
     * come together near a core. The places where it gets most of what it takes that way come first,
     * the nearest the places it was to look round first among those; what it can't get that way is led
     * to it as before.
     * @return where its main block would go
     */
    private Seq<int[]> docks(Blueprint blueprint, Seq<int[]> spots){
        Seq<int[]> out = new Seq<>();
        if((lines.router == null && lines.underflow == null) || spots.isEmpty() || late()) return out;
        Block main = blueprint.main();
        //for every tile of line that carries one of its supplies and nothing else, which of them
        IntIntMap carries = new IntIntMap();
        Seq<Item> items = new Seq<>();
        for(Port port : blueprint.ports){
            if(port.role != Role.in || items.contains(port.item) || port.item.id >= 31) continue;
            items.add(port.item);
            int bit = 1 << items.indexOf(port.item);
            IntSet has = new IntSet();
            float rate = wanted(main, port.item);
            for(var entry : net.carried(port.item)) if(entry.value >= rate * enough) has.add(entry.key);
            IntSet.IntSetIterator seen = brain.seen(port.item).iterator();
            while(seen.hasNext) has.add(seen.next());
            IntSet.IntSetIterator each = has.iterator();
            while(each.hasNext){
                int pos = each.next();
                if(pure(pos, port.item)) carries.put(pos, carries.get(pos, 0) | bit);
            }
        }
        if(items.isEmpty()) return out;
        int size = main.size, offset = -(size - 1) / 2;
        IntSet once = new IntSet();
        int looked = 0;
        for(var entry : carries){
            int pos = entry.key, x = Point2.x(pos), y = Point2.y(pos), near = Integer.MAX_VALUE;
            //a big base has thousands of tiles of line: this is not to take the time the placing itself needs
            if((++looked & 63) == 0 && (late() || looked > 4000)) break;
            for(int i = 0; i < spots.size; i++) near = Math.min(near, Math.max(Math.abs(spots.get(i)[0] - x), Math.abs(spots.get(i)[1] - y)) + i * 2);
            if(near > 40) continue;
            int flow = lines.runs(x, y);
            if(flow == -1) continue;
            for(int turn = 1; turn <= 3; turn += 2){
                int side = (flow + turn) % 4, sx = Geometry.d4x(side), sy = Geometry.d4y(side);
                for(int k = 0; k < size; k++){
                    int cx = sx != 0 ? (sx > 0 ? x + 1 - offset : x - size - offset) : x - offset - k;
                    int cy = sy != 0 ? (sy > 0 ? y + 1 - offset : y - size - offset) : y - offset - k;
                    if(!once.add(Point2.pack(cx, cy)) || !ground.clear(main, cx, cy)) continue;
                    //which of its supplies run along it here, each on a line of its own
                    int got = 0, x0 = cx + offset, y0 = cy + offset;
                    for(int i = 0; i < size; i++){
                        for(int s = 0; s < 4; s++){
                            int px = s == 0 ? x0 + size : s == 2 ? x0 - 1 : x0 + i, py = s == 1 ? y0 + size : s == 3 ? y0 - 1 : y0 + i;
                            got |= carries.get(Point2.pack(px, py), 0);
                        }
                    }
                    out.add(new int[]{cx, cy, near - Integer.bitCount(got) * 100});
                }
            }
        }
        out.sort(at -> at[2]);
        if(out.size > 40) out.truncate(40);
        return out;
    }

    /** Whether a tile of line carries the one item and nothing else, as far as is known: a line the base came with is taken to. */
    private boolean pure(int pos, Item item){
        int mix = net.mix().get(pos, 0);
        return mix == 0 || mix == 1 << item.id;
    }

    /**
     * Feeds what a port leads into out of a line that block stands against, if there is one that
     * carries the item. @return whether it is fed that way
     */
    private boolean dock(Project project, Layout layout, Port port, float rate){
        if(lines.router == null && lines.underflow == null) return false;
        int tx = port.x + Geometry.d4x(port.rotation), ty = port.y + Geometry.d4y(port.rotation);
        Part taker = layout.parts.find(part -> Shape.covers(part, tx, ty));
        if(taker == null) return false;
        int size = taker.block.size, offset = -(size - 1) / 2, x0 = taker.x + offset, y0 = taker.y + offset;
        IntSet body = new IntSet();
        for(int dx = 0; dx < size; dx++){
            for(int dy = 0; dy < size; dy++) body.add(Point2.pack(x0 + dx, y0 + dy));
        }
        IntFloatMap carried = net.carried(port.item);
        IntSet seen = brain.seen(port.item);
        for(int i = 0; i < size; i++){
            for(int side = 0; side < 4; side++){
                int x = side == 0 ? x0 + size : side == 2 ? x0 - 1 : x0 + i, y = side == 1 ? y0 + size : side == 3 ? y0 - 1 : y0 + i, pos = Point2.pack(x, y);
                if((carried.get(pos, 0f) < rate * enough && !seen.contains(pos)) || !pure(pos, port.item)) continue;
                //a way in that was made for something else it takes will do for this too
                boolean made = project.last.contains(part -> part.x == x && part.y == y && (part.block == lines.underflow || part.block == lines.router));
                if(!made){
                    Part head = lines.dock(x, y, body);
                    if(head == null) continue;
                    project.last.add(head);
                    ground.plan(head);
                }
                project.things.add(new Thing(Net.Kind.branch, x, y, port.item, rate));
                return true;
            }
        }
        return false;
    }

    private static String where(Seq<int[]> spots){
        StringBuilder out = new StringBuilder();
        for(int i = 0; i < Math.min(spots.size, 4); i++) out.append(i == 0 ? ": " : "; ").append(spots.get(i)[0]).append(',').append(spots.get(i)[1]);
        return out.toString();
    }

    /** How much of an item a second something uses when it is busy. */
    static float wanted(Block block, Item item){
        if(block instanceof UnitFactory factory && factory.plans.size > 0){
            var plan = factory.plans.first();
            for(ItemStack stack : plan.requirements){
                if(stack.item == item) return stack.amount * 60f / plan.time;
            }
        }
        if(block instanceof GenericCrafter) return Rates.uses(block, item);
        if(block instanceof Reconstructor rebuilder){
            for(mindustry.world.consumers.Consume consume : block.consumers){
                if(!(consume instanceof mindustry.world.consumers.ConsumeItems items)) continue;
                for(ItemStack stack : items.items){
                    if(stack.item == item) return stack.amount * 60f / rebuilder.constructTime;
                }
            }
        }
        if(block instanceof ItemTurret turret) return ammoUse(turret, item);
        return 0.5f;
    }

    /**
     * How much of an item a turret goes through in a fight: what it fires flat out, the rounds a shot takes and the
     * rounds an item of it makes taken into account, and much less, since it has a store of rounds to draw on and
     * does not fire all the time. A breach takes two rounds a shot: two beryllium, or one tungsten, which makes two. (The
     * bullets a shot scatters take nothing more.)
     */
    static float ammoUse(ItemTurret turret, Item item){
        var bullet = turret.ammoTypes.get(item);
        if(bullet == null) return 0.5f;
        float flatOut = 60f / Math.max(turret.reload, 1f) * turret.ammoPerShot / Math.max(bullet.ammoMultiplier, 0.1f);
        //in a fight a turret fires about a third of the time it stands there, and draws on its store of rounds between
        return Math.max(flatOut * 0.3f, 0.2f);
    }

    /** The block of a layout that a line coming in at a port ends on, if that is something that uses things up; else the layout's main block. */
    static Block taker(Layout layout, Port port, Block main){
        int x = port.x + Geometry.d4x(port.rotation), y = port.y + Geometry.d4y(port.rotation);
        for(Part part : layout.parts){
            if(Shape.covers(part, x, y) && (part.block instanceof UnitBlock || part.block instanceof GenericCrafter)) return part.block;
        }
        return main;
    }

    //endregion
    //region making better

    /**
     * Something that makes a liquid and needs no vent to stand on: put down where the liquids it is
     * made from can be piped to it, as near as may be to where what it makes is wanted.
     */
    private @Nullable Project springNear(Blueprint blueprint, int nearX, int nearY){
        note = blueprint.name + ": no place for it near " + nearX + "," + nearY;
        Block main = blueprint.main();
        //round what it is made from first, then round where it is wanted
        Seq<int[]> centres = new Seq<>();
        for(Plumbing.Tap need : Plumbing.needs(blueprint.parts)){
            Seq<Well> from = wells(need.liquid, nearX, nearY);
            for(int i = 0; i < Math.min(from.size, 2); i++) centres.add(new int[]{from.get(i).part.x, from.get(i).part.y});
        }
        centres.add(new int[]{nearX, nearY});

        int tries = 0;
        for(int[] centre : centres){
            int here = 0;
            around:
            for(int radius = 3; radius <= 9; radius++){
                for(int dx = -radius; dx <= radius; dx++){
                    for(int dy = -radius; dy <= radius; dy++){
                        if(Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;
                        int x = centre[0] + dx, y = centre[1] + dy;
                        if(!ground.clear(main, x, y)) continue;
                        if(late() || tries >= 8) return null;
                        if(here >= 3) break around;
                        if(!brain.reachable(main, x, y)) continue;
                        for(int way = 0; way < 4; way++){
                            Layout layout = blueprint.at(x, y, way, false, 1);
                            if(!clear(layout) || !fitter.valid(layout)) continue;
                            tries++;
                            here++;
                            Project project = new Project(blueprint.name);
                            lastTried = layout;
                            add(project, layout);
                            if(plumb(project, layout.parts) && energize(project, groups(layout.parts), powerUse(layout.parts)) && buildable(project)){
                                springs(project, layout.parts);
                                project.assets.add(new Asset(Net.Kind.spring, layout, layout.main().x, layout.main().y));
                                return project;
                            }
                            note = blueprint.name + ": " + (lacking != null ? "no " + lacking.name + " to be had" : "no power to be had") + " at " + x + "," + y;
                            brain.syncGround();
                            break;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * So many of a block put up by themselves near a core: for when a map asks for a block to be
     * built and nothing the enemy builds of its own accord has brought it. One that has to stand on a
     * vent goes on the nearest free ones.
     */
    public @Nullable Project erect(Block block, int count){
        note = block.name + ": nowhere near a core to put it";
        Building core = territory.focus != null ? territory.focus : ground.team.core();
        if(core == null || late()) return null;
        Project project = new Project(count + " " + block.name);
        if(onVent(block)){
            IntSeq vents = brain.terrain.vents(block, ground.team);
            Seq<int[]> near = new Seq<>();
            for(int i = 0; i < vents.size; i++) near.add(new int[]{Point2.x(vents.get(i)), Point2.y(vents.get(i))});
            near.sort(at -> Math.abs(at[0] - core.tileX()) + Math.abs(at[1] - core.tileY()));
            for(int[] at : near){
                if(project.parts.size >= count) break;
                if(ground.fits(block, at[0], at[1], 0) && brain.reachable(block, at[0], at[1])) add(project, Seq.with(new Part(block, at[0], at[1], 0)));
            }
        }else{
            search:
            for(int radius = core.block.size / 2 + 3; radius <= 30; radius++){
                for(int dx = -radius; dx <= radius; dx++){
                    for(int dy = -radius; dy <= radius; dy++){
                        if(Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;
                        if(project.parts.size >= count || late()) break search;
                        int x = core.tileX() + dx, y = core.tileY() + dy;
                        //room round it as well: something put down for the asking is not to shut in what stands there
                        if(!ground.fits(block, x, y, 0) || !brain.reachable(block, x, y) || lines.pocket(x, y, none, -1)) continue;
                        add(project, Seq.with(new Part(block, x, y, 0)));
                    }
                }
            }
        }
        return project.parts.isEmpty() ? null : project;
    }

    /**
     * Something that sees a long way, such as a radar, on ground it has seen, as near as it will go to a
     * place it has not, with its power. Nothing can be built on ground no one of its side has seen; where
     * none of its units can get to look, this is how that ground is uncovered.
     */
    public @Nullable Project lookout(Block block, int tx, int ty){
        note = block.name + ": no seen ground near " + tx + "," + ty;
        if(late()) return null;
        Team team = ground.team;
        //seen ground nearest the place that its own ground runs on to over land, where its power can be led: from there it
        //uncovers the place, or as much of the way to it as it sees
        int reach = Math.max(block.fogRadius - 2, 4) + 16;
        Seq<int[]> spots = new Seq<>();
        for(int dx = -reach; dx <= reach; dx++){
            for(int dy = -reach; dy <= reach; dy++){
                int x = tx + dx, y = ty + dy;
                if(dx * dx + dy * dy > reach * reach || !world.isInMapArea(x, y)) continue;
                if(state.rules.fog && !fogControl.isDiscovered(team, x, y)) continue;
                if(territory.steps(x, y) < 0 || brain.avoided(Task.Kind.radar, brain.atlas.zoneAt(x, y)) || !ground.fits(block, x, y, 0) || !brain.reachable(block, x, y)) continue;
                //where one of these stands already, another would see nothing more
                if(indexer.findTile(team, x * tilesize, y * tilesize, tilesize * 8f, build -> build.block == block) != null) continue;
                spots.add(new int[]{x, y, dx * dx + dy * dy});
            }
        }
        spots.sort(at -> at[2]);
        for(int i = 0; i < Math.min(spots.size, 12) && !late(); i++){
            int[] at = spots.get(i);
            Project project = new Project(block.name + " to look at " + tx + "," + ty);
            Seq<Part> parts = Seq.with(new Part(block, at[0], at[1], 0));
            //a node beside it, which its power is led to
            if(block.consPower != null && power.node != null){
                Part node = null;
                for(int d = 0; d < 4 && node == null; d++){
                    int nx = at[0] + Geometry.d4x(d), ny = at[1] + Geometry.d4y(d);
                    if(ground.fits(power.node, nx, ny, 0) && (!state.rules.fog || fogControl.isDiscovered(team, nx, ny))) node = new Part(power.node, nx, ny, 0);
                }
                if(node == null) continue;
                parts.add(node);
            }
            add(project, parts);
            //what it draws is little, and it is wanted for what it opens up: it may hang on a grid that is short already
            boolean before = needy;
            needy = true;
            boolean powered;
            try{
                powered = energize(project, groups(parts), powerUse(parts));
            }finally{
                needy = before;
            }
            if(!powered){
                drop(project, "no power to be had at " + at[0] + "," + at[1]);
                continue;
            }
            if(buildable(project)) return project;
            drop(project, "the game does not allow " + refused);
        }
        return null;
    }

    /**
     * Adds to something that stands: the parts of an addition, put where the blueprint has them from
     * the block it is added to, with their lines, their liquids and their power. A refabricator goes
     * in front of the fabricator whose units it is to take.
     */
    public @Nullable Project addon(Blueprint blueprint, Building anchor){
        lacking = null;
        if(late()) return null;
        String where = " at " + anchor.tileX() + "," + anchor.tileY();
        note = blueprint.name + ": no room for it" + where;
        String failed = null;
        for(int flip = 0; flip < 2 && !late(); flip++){
            Layout whole = blueprint.at(anchor.tileX(), anchor.tileY(), anchor.block.rotate ? anchor.rotation : 0, flip == 1, 1);
            Layout layout = new Layout(blueprint);
            layout.parts.addAll(whole.parts.select(part -> !part.anchor));
            layout.ports.addAll(whole.ports);
            layout.turns = whole.turns;
            layout.mirrored = whole.mirrored;
            //the ground a unit yard keeps clear in front of itself is where what takes its units goes
            tiles.clear();
            for(Part part : layout.parts) Shape.tiles(part, tiles);
            front.clear();
            brain.frontOf(anchor.block, anchor.tileX(), anchor.tileY(), anchor.rotation, front);
            //all of it: the addition stands on the nearer part, and lets the units out over the rest
            for(int i = 0; i < front.size; i++) ground.kept.remove(front.get(i));
            trim(layout);
            boolean room = clear(layout), valid = room && fitter.valid(layout), exits = valid && exitsClear(layout, false, false);
            if(!exits){
                Part blocked = layout.parts.find(part -> !ground.clear(part.block, part.x, part.y));
                note = blueprint.name + ": " + (blocked != null ? "no room for its " + blocked.block.name + " at " + blocked.x + "," + blocked.y
                    : !room ? "no way in to where its lines would end" : !valid ? "would stand against a line it is not to take from" : "its units could not get out") + where;
                brain.syncGround();
                continue;
            }

            Project project = new Project(blueprint.name);
            lastTried = layout;
            add(project, layout);
            boolean supplied = true;
            for(Port port : layout.ports){
                if(port.role != Role.in) continue;
                holdPorts(layout, port, true);
                boolean fed = feed(project, port, wanted(taker(layout, port, layout.main().block), port.item));
                holdPorts(layout, port, false);
                if(!fed){
                    supplied = false;
                    if(failed == null) failed = "no line to branch " + port.item.name + " off within reach" + where + " [" + feedNote + "]";
                    break;
                }
            }
            if(supplied && !plumb(project, layout.parts)){
                //with none to be had the place is good all the same: what it lacks can be built, and then this is taken up again
                if(lacking != null) return drop(project, "no " + lacking.name + " to be had" + where);
                supplied = false;
                failed = "no way to lay a conduit" + where;
            }
            if(supplied && !energize(project, groups(layout.parts), powerUse(layout.parts))){
                supplied = false;
                failed = "no power to be had" + where;
            }
            if(supplied && buildable(project)){
                springs(project, layout.parts);
                return project;
            }
            brain.syncGround();
        }
        if(failed != null) note = blueprint.name + ": " + failed;
        return null;
    }

    private final IntSeq front = new IntSeq();

    /**
     * Lets a liquid that makes its blocks faster into something that stands. Where its blueprint says
     * where, liquid routers go on those tiles, each standing against what it is for, and the liquid
     * is piped to them; a router is not solid, so one may stand under the beams of a drill. Where
     * the blueprint says nothing the conduit runs straight to the block.
     * @return what to build, or null: {@link #lacking} then says if it is the liquid that is not to be had
     */
    public @Nullable Project boost(Layout layout, Liquid liquid, @Nullable Block router){
        if(pipes.duct == null || late()) return null;
        note = "no way to let " + liquid.name + " into the " + layout.blueprint.name + " at " + layout.main().x + "," + layout.main().y;
        Project project = new Project(liquid.name + " for " + layout.blueprint.name);

        //what takes it, and how much of it in all
        Seq<Part> takers = new Seq<>();
        float rate = 0f;
        for(Part part : layout.parts){
            for(Rates.Flow flow : Rates.liquidBoosts(part.block)){
                if(flow.liquid != liquid || !ground.look(part.x, part.y) || ground.block != part.block) continue;
                takers.add(part);
                rate += flow.rate;
            }
        }
        if(takers.isEmpty()) return null;

        Seq<IntSet> ways = new Seq<>();
        if(layout.boosts.contains(boost -> boost.liquid == liquid)){
            if(router == null) return null;
            //the routers that can go up, and with them the ones that stand: those that touch hand the liquid on between them
            Seq<Part> routers = new Seq<>();
            IntSet all = new IntSet();
            for(Boost boost : layout.boosts){
                if(boost.liquid != liquid) continue;
                int pos = Point2.pack(boost.x, boost.y);
                if(ground.look(boost.x, boost.y) && ground.block == router){
                    all.add(pos);
                    continue;
                }
                //what was kept clear for a beam may hold a router: beams pass over those
                boolean kept = ground.kept.remove(pos);
                if(ground.fits(router, boost.x, boost.y, 0)){
                    all.add(pos);
                    routers.add(new Part(router, boost.x, boost.y, 0));
                }else if(kept){
                    ground.kept.add(pos);
                }
            }
            //each group of routers that touch is one place to pipe to; the biggest first
            IntSet left = new IntSet(all);
            while(!left.isEmpty()){
                IntSet group = new IntSet();
                IntSeq open = new IntSeq();
                open.add(left.first());
                while(open.size > 0){
                    int pos = open.pop();
                    if(!left.remove(pos)) continue;
                    group.add(pos);
                    for(int d = 0; d < 4; d++) open.add(Point2.pack(Point2.x(pos) + Geometry.d4x(d), Point2.y(pos) + Geometry.d4y(d)));
                }
                ways.add(group);
            }
            ways.sort(group -> -group.size);
            if(ways.size > 2) ways.truncate(2);
            IntSet used = new IntSet();
            for(IntSet group : ways) used.addAll(group);
            routers.removeAll(part -> !used.contains(Point2.pack(part.x, part.y)));
            if(ways.isEmpty()) return drop(project, "no room for a router by it");
            add(project, routers);
        }else{
            //straight into each block that takes it: fine for one or two, not for a row
            if(takers.size > 2) return null;
            for(Part part : takers){
                IntSet into = new IntSet();
                Shape.tiles(part, into);
                ways.add(into);
            }
        }

        boolean any = false;
        lacking = null;
        for(IntSet into : ways){
            int pos = into.first();
            boolean steady = !takers.contains(part -> part.block instanceof BaseTurret);
            float left = pipe(project, liquid, rate / ways.size, into, Point2.x(pos), Point2.y(pos), steady);
            //something is better than nothing: what makes it faster makes it faster by as much as there is of it
            if(left < rate / ways.size * 0.7f) any = true;
        }
        if(!any) return drop(project, (lacking != null ? "no " + liquid.name + " to spare within reach of " : "no way to lay a conduit to ") + layout.main().x + "," + layout.main().y);
        lacking = null;
        if(!buildable(project)) return drop(project, "the game does not allow " + refused);
        return project;
    }

    /**
     * Lays conduits that bring so much of a liquid to a place, from whatever of the enemy's has it to spare.
     * @param into the tiles the liquid has to arrive in
     * @return how much of what was asked for could not be had
     */
    private float pipe(Project project, Liquid liquid, float rate, IntSet into, int x, int y){
        return pipe(project, liquid, rate, into, x, y, true);
    }

    /**
     * @param reserve whether what is led off is set aside for where it goes. Not so for the water a turret is cooled with:
     * it takes that only while it is loading, the conduit stands full the rest of the time, and what a drill or a
     * factory needs all the time is not to go short for it
     */
    private float pipe(Project project, Liquid liquid, float rate, IntSet into, int x, int y, boolean reserve){
        int limit = reachOfPipes();
        float left = rate, there = 0f;
        IntSet joined = new IntSet();
        for(Well well : wells(liquid, x, y)){
            if(left <= 0.05f || late()) break;
            if(Math.abs(well.part.x - x) + Math.abs(well.part.y - y) > limit) break;
            there += well.spare;
            Seq<int[]> outlets = Plumbing.outlets(well.part, well.side, ground);
            outlets.sort(at -> Math.abs(at[0] - x) + Math.abs(at[1] - y));
            tiles.clear();
            Shape.tiles(well.part, tiles);
            for(int i = 0; i < Math.min(outlets.size, 3); i++){
                int[] at = outlets.get(i);
                Lines.Line line = pipes.route(at[0], at[1], at[2], tiles, Lines.Goal.into(into, x, y, joined.isEmpty() ? null : joined));
                if(line == null || line.parts.size > limit) continue;
                line.parts.reverse();
                add(project, line.parts);
                for(Part part : line.parts) joined.add(Point2.pack(part.x, part.y));
                float taken = Math.min(well.spare, left);
                project.things.add(new Thing(Net.Kind.pipe, well.part.x, well.part.y, liquid, reserve ? taken : 0f));
                left -= taken;
                break;
            }
        }
        //with too little of it anywhere near, more has to be made; with enough of it and no way found, it is the place that is wrong
        if(left > rate * 0.2f && there < rate * 0.8f){
            lacking = liquid;
            lackX = x;
            lackY = y;
        }
        return left;
    }

    /**
     * Gives a turret, or a group of them, better ammunition: a line of it is led off wherever enough of
     * it goes by and run into the line that supplies the turrets now, and the place that one was led
     * off the old ammunition is made plain duct again. What stands between the old line's end and
     * the turrets, the router that shares it out among a group, serves as before.
     * @param old where the ammunition it has now is led off for it
     */
    public @Nullable Project rearm(String name, Thing old, Item best, float rate){
        if(late()) return null;
        note = best.name + " for " + name + ": the line that supplies it could not be found";
        int start = net.spur(old.x, old.y);
        if(start == -1) return null;
        IntSeq way = new IntSeq(net.trace(Point2.x(start), Point2.y(start)));
        int endX = net.endX, endY = net.endY;
        if(way.isEmpty()) return null;
        IntSet joins = new IntSet();
        for(int i = 0; i < way.size; i++) joins.add(way.get(i));

        Project project = new Project(best.name + " for " + name);
        if(!feed(project, best, endX, endY, rate, Lines.Goal.into(none, endX, endY, joins), null)){
            return drop(project, "no line with enough " + best.name + " to lead it off from [" + feedNote + "]");
        }
        if(!buildable(project)) return drop(project, "the game does not allow " + refused);
        //put up last, so that the turrets are fed until the new line is there
        if(ground.look(old.x, old.y)) project.last.add(new Part(lines.duct, old.x, old.y, ground.rotation));
        project.forget.add(old);
        return project;
    }

    /**
     * More power for a grid that can't keep up with what is on it: a generator on a free vent, joined
     * to the grid by a chain of nodes, or a chain to another grid that has power to spare.
     * @param nodes beam nodes of the grid, to lead the chain from
     * @param needed how much it is short, a tick
     */
    public @Nullable Project power(Seq<Part> nodes, float needed){
        if(late() || nodes.isEmpty()) return null;
        note = "generator: no free vent, and no grid with power to spare, within reach of the grid at " + nodes.first().x + "," + nodes.first().y;
        Seq<Part> extra = power.link(nodes, needed, none);
        if(extra == null || extra.isEmpty()) return null;
        Project project = new Project("generator");
        add(project, extra);
        if(!buildable(project)) return drop(project, "the game does not allow " + refused);
        return project;
    }

    /**
     * Gets power to a block that stands without any: one that was put up bare because a map asked for
     * it, one the map gave it that way, or one whose nodes were destroyed. It is joined to a grid with
     * power to spare, or to a generator put up for it.
     */
    public @Nullable Project wireUp(Building build){
        if(late()) return null;
        Project project = new Project("power for " + build.block.name);
        Seq<Part> parts = Seq.with(new Part(build.block, build.tileX(), build.tileY(), build.rotation));
        if(!energize(project, groups(parts), powerUse(parts))) return drop(project, "no power to be had for the " + build.block.name + " at " + build.tileX() + "," + build.tileY());
        if(project.parts.isEmpty() || !buildable(project)) return drop(project, project.parts.isEmpty() ? "nothing to join the " + build.block.name + " to" : "the game does not allow " + refused);
        return project;
    }

    /**
     * Puts up the parts of a layout it did without when it was built, now that there is the stuff for
     * them, and puts better in the place of the ones that stand: walls, in the blueprints there are.
     * The game lets a wall be built over a lesser wall as it stands.
     */
    public @Nullable Project fortify(Layout layout){
        if(late()) return null;
        Project project = new Project("walls for " + layout.blueprint.name);
        for(Part part : layout.parts){
            if(!part.optional) continue;
            Block best = brain.spared(part.block);
            if(best == null) continue;
            Building standing = world.build(part.x, part.y);
            if(standing != null && standing.team == ground.team && standing.tileX() == part.x && standing.tileY() == part.y){
                //something else was put there since, or it is as good as it gets
                if(standing.block == best || standing.block.size != best.size || !brain.better(best, standing.block)) continue;
                if(ground.planned(part.x, part.y) != null || !Build.validPlace(best, ground.team, part.x, part.y, part.rotation)) continue;
            }else if(!ground.fits(best, part.x, part.y, part.rotation)){
                continue;
            }
            Part wall = new Part(best, part.x, part.y, part.rotation, part.config);
            ground.plan(wall);
            project.parts.add(wall);
        }
        if(project.parts.isEmpty()){
            note = "walls for " + layout.blueprint.name + ": all it can have";
            return null;
        }
        return project;
    }

    //endregion
}
