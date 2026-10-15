package expansion;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import expansion.Blueprint.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.storage.*;

import static mindustry.Vars.*;

/**
 * Lays a line of ducts from where items come out to where they are wanted.
 *
 * The line goes over open ground only, turning where it has to, and takes a bridge over whatever is in
 * the way when that is shorter than going round. It ends facing a core, or the side of one of the
 * enemy's own lines that already runs to a core, or on the tile and facing the way an input asks for.
 *
 * A duct takes items from behind and from both sides. So a line is kept clear of everything that would
 * push items into it that are not meant for it: the next drill along, a factory, the open side of a
 * router. Otherwise it would carry things its end can't take, and stop.
 */
public class Lines{
    /**
     * Whether this lays conduits for liquids instead of ducts for items. The search is the same; what
     * differs is what a line has to be kept clear of. A conduit takes liquid from other conduits behind
     * it and at its sides, but from anything else only when it points straight away from it. So a
     * conduit may run past a pump or a tank untouched, and must not have one at its back.
     */
    public boolean liquid;
    /** What laying a line costs, in the terms the search works with: a duct is 1. */
    static final float turnCost = 1.2f, bridgeCost = 9f, wallCost = 8f;
    /** What running alongside a duct already laid costs, a tile. (for comparing, -Dxp.beside) */
    static final float besideCost = 2.5f;
    /**
     * What laying on the ground drills mine from costs, a tile: right in front of a vein of ore in a wall, or on ore in the
     * floor. That is where drills have to stand, and a line laid there takes the place for good (a line run along the face
     * of a vein near home left four tiles of the only graphite in reach unmined for the rest of the game).
     */
    static final float oreCost = 3f;
    /** Whether a line to a core may go through the enemy's own walls, which are then taken down for it. */
    public boolean walls = true;
    /** How many places one search looks at before giving up: for a line to a core, and for one to an input close by. */
    public int budget = 40000, shortBudget = 9000;
    /** Why ways were shut in the last search, for looking into it: something of its own there, ground that is not open, something that would push into a duct there, no bridge from there. */
    public final int[] shutBy = new int[4];
    /**
     * How many places all the searches together may still look at. Working out one thing to build can
     * take many searches, and it all happens between two frames of the game, so the total is bounded too.
     */
    public int allowance = Integer.MAX_VALUE;
    /** How many places the last search looked at. If that is short of the budget, it ran out of ground to try. */
    public int lastLooked;
    /** When a search has to stop whatever it has found, as the clock the game runs on has it. One long search can use up all the time there is on its own. */
    public long deadline = Long.MAX_VALUE;

    /** How far a tile is from the nearest core over open ground. */
    public interface Far{
        int get(int x, int y);
    }

    /** Given where it is known: what a line to a core steers by. */
    public @Nullable Far homeward;

    private final Ground ground;
    //none of these can be filled in here: this is made when the mod is loaded, which is before the game has made its blocks
    public Block duct;
    /** Left null where the enemy may not build bridges. */
    public @Nullable Block bridge;
    /** What a line is branched with. Left null where the enemy may not build it. */
    public @Nullable Block router;
    /**
     * What a line that carries one thing is led off with, where the enemy may build it: it hands
     * what comes by to the line at its side first, and lets it go on down the old line only when
     * that one is full. So what was led off gets all it needs before anything goes past.
     */
    public @Nullable Block underflow;

    private float[] cost = {};
    private int[] parent = {}, stamp = {};
    private byte[] jump = {};
    //what was found out about each tile in the course of one search: nothing changes on the ground while it runs
    private int[] tileStamp = {}, usedStamp = {};
    private byte[] tileOpen = {}, tilePushed = {}, tileGives = {}, tileBeside = {}, tileOre = {};
    private int generation, width, height;
    private final IntSeq heap = new IntSeq();
    private final IntSet allowed = new IntSet();
    private final IntSeq joinAt = new IntSeq();
    private final Seq<Building> cores = new Seq<>();

    /** Where a line is to end. */
    public static class Goal{
        /** At any core of the team. */
        public boolean core;
        /** At the side of a duct on one of these tiles. */
        public @Nullable IntSet lines;
        /** On this tile, facing this way. */
        public boolean port;
        public int portX, portY, portRotation;
        /** Facing any of these tiles from outside them: the sides of something that takes what the line brings. */
        public @Nullable IntSet into;
        /** Roughly where that is, to steer by. */
        public int intoX, intoY;

        public static Goal core(@Nullable IntSet lines){
            Goal goal = new Goal();
            goal.core = true;
            goal.lines = lines;
            return goal;
        }

        public static Goal into(IntSet tiles, int x, int y, @Nullable IntSet lines){
            Goal goal = new Goal();
            goal.into = tiles;
            goal.intoX = x;
            goal.intoY = y;
            goal.lines = lines;
            return goal;
        }

        /** Whether the end is somewhere close by that is known beforehand. */
        boolean near(){
            return port || into != null;
        }

        public static Goal port(Port port){
            Goal goal = new Goal();
            goal.port = true;
            goal.portX = port.x;
            goal.portY = port.y;
            goal.portRotation = port.rotation;
            return goal;
        }
    }

    /** A line that was found. */
    public static class Line{
        /** The ducts and bridges, in the order items pass through them. */
        public final Seq<Part> parts = new Seq<>();
        /** The tile the line empties into. */
        public int endX, endY;
        /** Whether it joins another line rather than reaching a core or an input. */
        public boolean joins;
        /** Walls of the enemy's own that stand where the line goes and have to come down first, by the tile each is built from. */
        public final IntSeq removals = new IntSeq();
    }

    public Lines(Ground ground){
        this.ground = ground;
    }

    /**
     * How far the open ground round some tiles is from them, going over open ground and stepping
     * across whatever a bridge could cross. Rough, and quick: which way anything faces is not asked.
     * It tells, before a line is looked for in earnest, which of the places the line could start from
     * can be got to at all, and which of those is nearest by the way a line would have to go. The
     * nearest as the crow flies is often shut in behind other lines, in a base that has grown.
     * @return for every tile that was reached, how many tiles of line it is away
     */
    public IntIntMap reachFrom(IntSeq starts, int limit){
        IntIntMap out = new IntIntMap();
        IntQueue queue = new IntQueue();
        int hops = bridge instanceof DirectionBridge across ? across.range : 0;
        ground.laying = true;
        try{
            for(int i = 0; i < starts.size; i++){
                int pos = starts.get(i);
                if(ground.open(Point2.x(pos), Point2.y(pos)) && !out.containsKey(pos)){
                    out.put(pos, 0);
                    queue.addLast(pos);
                }
            }
            while(!queue.isEmpty()){
                int pos = queue.removeFirst(), x = Point2.x(pos), y = Point2.y(pos), steps = out.get(pos, 0);
                if(steps >= limit) continue;
                for(int d = 0; d < 4; d++){
                    int dx = Geometry.d4x(d), dy = Geometry.d4y(d);
                    for(int hop = 1; hop <= Math.max(hops, 1); hop++){
                        int next = Point2.pack(x + dx * hop, y + dy * hop);
                        if(!ground.open(x + dx * hop, y + dy * hop)) continue;
                        if(!out.containsKey(next)){
                            out.put(next, steps + hop);
                            queue.addLast(next);
                        }
                        break;
                    }
                }
            }
        }finally{
            ground.laying = false;
        }
        return out;
    }

    /**
     * @param feeders tiles of the blocks that are meant to push into the start of the line
     * @return the line, or null if there is no way within the budget
     */
    public @Nullable Line route(int startX, int startY, IntSet feeders, Goal goal){
        return route(startX, startY, -1, feeders, goal);
    }

    /** @param facing the way the first piece of the line has to face, or -1 for any way */
    public @Nullable Line route(int startX, int startY, int facing, IntSet feeders, Goal goal){
        long prof = Prof.start();
        ground.laying = true;
        try{
            return search(startX, startY, facing, feeders, goal);
        }finally{
            ground.laying = false;
            Prof.end(liquid ? "conduit" : goal.near() ? "line to input" : "line to core", prof);
        }
    }

    private @Nullable Line search(int startX, int startY, int facing, IntSet feeders, Goal goal){
        width = world.width();
        height = world.height();
        int size = width * height * 4;
        if(cost.length != size){
            cost = new float[size];
            parent = new int[size];
            stamp = new int[size];
            jump = new byte[size];
            tileStamp = new int[size / 4];
            usedStamp = new int[size / 4];
            tileOpen = new byte[size / 4];
            tilePushed = new byte[size / 4];
            tileGives = new byte[size / 4];
            tileBeside = new byte[size / 4];
            tileOre = new byte[size / 4];
            generation = 0;
        }
        generation++;
        java.util.Arrays.fill(shutBy, 0);
        heap.clear();
        cores.clear();
        if(goal.core) cores.addAll(ground.team.cores());
        //a few of the places where the line could join another, to steer by
        joinAt.clear();
        if(goal.core && goal.lines != null){
            IntSet.IntSetIterator each = goal.lines.iterator();
            while(each.hasNext && joinAt.size < 400){
                int pos = each.next(), jx = Point2.x(pos), jy = Point2.y(pos);
                if((jx + jy) % 5 == 0) joinAt.add(jx, jy);
            }
        }

        if(startX < 0 || startY < 0 || startX >= width || startY >= height || !ground.open(startX, startY)) return null;
        for(int d = 0; d < 4; d++){
            //the first duct must not face back into what feeds it
            if((facing != -1 && d != facing) || feeders.contains(Point2.pack(startX + Geometry.d4x(d), startY + Geometry.d4y(d))) || fed(startX, startY, d, feeders)) continue;
            visit(state(startX, startY, d), -1, 0f, 0, goal);
        }

        int looked = 0, limit = Math.min(goal.near() ? shortBudget : budget, allowance);
        lastLooked = 0;
        while(heap.size > 0 && looked++ < limit){
            allowance--;
            lastLooked = looked;
            Clock.spend(1);
            if((looked & 127) == 0 && Clock.now() > deadline) return null;
            int current = pop();
            int tile = current / 4, d = current % 4, x = tile % width, y = tile / width;
            int fx = x + Geometry.d4x(d), fy = y + Geometry.d4y(d);

            if(reached(x, y, d, fx, fy, goal)) return line(current, fx, fy, goal);

            float base = cost[current];
            if(fx < 0 || fy < 0 || fx >= width || fy >= height) continue;
            look(fx, fy);
            int at = fx + fy * width, back = (d + 2) % 4;
            boolean open = tileOpen[at] == 1;
            //a line to a core goes through the enemy's own walls where it has to: they are taken down for it
            if(!open && !(goal.core && walls && ground.removable(fx, fy))){
                shutBy[ground.look(fx, fy) ? 1 : 0]++;
                continue;
            }

            //a line can't cross itself: a tile holds one duct, facing one way
            if(usedStamp[at] == generation && onPath(current, fx, fy)) continue;

            //what would push into a duct there, other than the duct this one comes from
            int pushed = tilePushed[at] & ~(1 << back), gives = tileGives[at];
            if(pushed != 0 && Integer.bitCount(pushed) > 1) shutBy[2]++;
            for(int next = 0; next < 4; next++){
                //a duct takes nothing from the block it faces
                if(next == back || (pushed & ~(1 << next)) != 0) continue;
                //and a conduit takes from whatever makes or holds liquid right behind it, which only the one it comes from may be
                if(liquid && next != d && (gives & (1 << ((next + 2) % 4))) != 0) continue;
                //right up against another line costs extra: a line with one hard by it on either side can never have anything
                //led off it, as whatever was put in it to do that would hand things to the other line too
                visit(state(fx, fy, next), current, base + 1f + (next == d ? 0f : turnCost) + (open ? 0f : wallCost) + tileBeside[at] * besideCost + tileOre[at] * oreCost, 0, goal);
            }
            if(!open) continue;
            allowed.clear();
            allowed.add(Point2.pack(x, y));

            //a bridge from the tile in front, straight on
            if(bridge == null) continue;
            if(fed(fx, fy, d, allowed) || bridgeClash(fx, fy, d, false, -1, -1)){
                shutBy[3]++;
                continue;
            }
            int range = ((DirectionBridge)bridge).range;
            for(int length = 2; length <= range; length++){
                int ex = fx + Geometry.d4x(d) * length, ey = fy + Geometry.d4y(d) * length;
                //nothing in between may be a bridge: the first one along is the one a bridge links to
                int mx = ex - Geometry.d4x(d), my = ey - Geometry.d4y(d);
                if(ground.look(mx, my) && ground.block == bridge) break;
                if(ex < 0 || ey < 0 || ex >= width || ey >= height) break;
                if(!ground.open(ex, ey) || fed(ex, ey, d, noTiles) || bridgeClash(ex, ey, d, true, fx, fy)) continue;
                if(usedStamp[ex + ey * width] == generation && onPath(current, ex, ey)) continue;
                visit(state(ex, ey, d), current, base + bridgeCost + length, length, goal);
            }
        }
        return null;
    }

    private static final IntSet noTiles = new IntSet();

    /**
     * Takes one kind of item off a line that carries it, and leads it to where it is wanted.
     *
     * The duct at the given place becomes a router, which hands part of what comes by to a second
     * router at its side. That one is set to the item: the item goes straight on, into the new line,
     * and anything else goes out of its side into a short duct that puts it back on the line it
     * came from. So the new line carries the one item only, and when its end is full the first router
     * simply sends everything on down the old line.
     *
     * @param x a duct of the line, with the one before it right behind and the one after right in front
     * @return the line with the routers at its head, or null if it can't be done here
     */
    public @Nullable Line branch(int x, int y, mindustry.type.Item item, Goal goal){
        if(router == null || !plainDuct(x, y) || !ground.rule.allows(x, y)) return null;
        int flow = ground.rotation;
        int fx = Geometry.d4x(flow), fy = Geometry.d4y(flow);
        //fed from right behind, as a router takes nothing from its sides; and a plain duct in front to take things back
        if(!ground.pushesInto(x - fx, y - fy, x, y) || !plainDuct(x + fx, y + fy) || ground.rotation == (flow + 2) % 4) return null;
        int after = ground.rotation;

        for(int turn = 1; turn <= 3; turn += 2){
            int side = (flow + turn) % 4, sx = Geometry.d4x(side), sy = Geometry.d4y(side);
            int sortX = x + sx, sortY = y + sy, backX = sortX + fx, backY = sortY + fy;
            //the duct in front must not turn this way itself
            if(after == side) continue;
            if(!ground.open(sortX, sortY) || !ground.open(backX, backY) || !ground.open(sortX + sx, sortY + sy)) continue;
            //nothing may stand where the routers would push things that is not meant to get them
            if(takes(x - sx, y - sy) || takes(sortX - fx, sortY - fy)) continue;
            allowed.clear();
            allowed.add(Point2.pack(sortX, sortY));
            if(fed(backX, backY, (side + 2) % 4, allowed)) continue;

            Seq<Part> head = Seq.with(
                new Part(router, x, y, flow),
                new Part(router, sortX, sortY, side, item),
                new Part(duct, backX, backY, (side + 2) % 4)
            );
            //the duct that becomes a router may itself only be planned so far
            Part before = ground.planned(x, y);
            ground.plan(head);
            Line line = route(sortX + sx, sortY + sy, IntSet.with(Point2.pack(sortX, sortY)), goal);
            ground.unplan(head);
            if(before != null) ground.plan(before);
            if(line == null) continue;
            head.addAll(line.parts);
            line.parts.clear();
            line.parts.addAll(head);
            return line;
        }
        return null;
    }

    /**
     * Whether the duct here is one that something standing at its side could be fed out of: a plain
     * duct in the run of a line, with a duct behind it that feeds it and one in front to take on what
     * is not handed out.
     * @return the way the line runs here, or -1
     */
    public int runs(int x, int y){
        if(!plainDuct(x, y) || !ground.rule.allows(x, y)) return -1;
        int flow = ground.rotation, fx = Geometry.d4x(flow), fy = Geometry.d4y(flow);
        if(!ground.pushesInto(x - fx, y - fy, x, y) || !plainDuct(x + fx, y + fy) || ground.rotation == (flow + 2) % 4) return -1;
        return flow;
    }

    /**
     * Feeds a block that stands right against a line, with no branch at all: the duct beside it
     * becomes one that hands what comes by to its sides first, and lets go on down the line whatever
     * the block does not take. On a line that carries several things the block takes those it has a
     * use for and the rest goes by. This is how a base is fed off a few trunk lines: things stand
     * along them, and nothing has to be led through the ground between.
     * @param taker the tiles of the block
     * @return what the duct is to become, or null if the block can't be fed out of this duct
     */
    public @Nullable Part dock(int x, int y, IntSet taker){
        Block with = underflow != null ? underflow : router;
        int flow = runs(x, y);
        if(with == null || flow == -1) return null;
        for(int turn = 1; turn <= 3; turn += 2){
            int side = (flow + turn) % 4, sx = Geometry.d4x(side), sy = Geometry.d4y(side);
            if(!taker.contains(Point2.pack(x + sx, y + sy))) continue;
            //it hands things out of its other side too: nothing may stand there that would take them
            if(takes(x - sx, y - sy)) continue;
            return new Part(with, x, y, flow);
        }
        return null;
    }

    /**
     * Leads a line off one that carries a single kind of item, the way the bases on the game's own
     * maps do it: the duct at the given place becomes a plain router, and the new line starts at its
     * side. The router shares what comes by between the two, and when the new line is full everything
     * goes on down the old one.
     * @return the line with the router at its head, or null if it can't be done here
     */
    public @Nullable Line tap(int x, int y, Goal goal){
        Block with = underflow != null ? underflow : router;
        if(with == null || !plainDuct(x, y) || !ground.rule.allows(x, y)) return null;
        int flow = ground.rotation;
        int fx = Geometry.d4x(flow), fy = Geometry.d4y(flow);
        if(!ground.pushesInto(x - fx, y - fy, x, y) || !plainDuct(x + fx, y + fy) || ground.rotation == (flow + 2) % 4) return null;

        for(int turn = 1; turn <= 3; turn += 2){
            int side = (flow + turn) % 4, sx = Geometry.d4x(side), sy = Geometry.d4y(side);
            //the router hands things out of its other side too: nothing may stand there that would take them
            if(!ground.open(x + sx, y + sy) || takes(x - sx, y - sy)) continue;
            Part head = new Part(with, x, y, flow);
            //the duct that becomes a router may itself only be planned so far
            Part before = ground.planned(x, y);
            ground.plan(head);
            Line line = route(x + sx, y + sy, IntSet.with(Point2.pack(x, y)), goal);
            ground.unplan(Seq.with(head));
            if(before != null) ground.plan(before);
            if(line == null) continue;
            line.parts.insert(0, head);
            return line;
        }
        return null;
    }

    /** Whether a line could be led off this duct with a plain router, as far as the duct and its neighbours go. */
    public boolean taps(int x, int y){
        if((router == null && underflow == null) || !plainDuct(x, y) || !ground.rule.allows(x, y)) return false;
        int flow = ground.rotation, fx = Geometry.d4x(flow), fy = Geometry.d4y(flow);
        if(!ground.pushesInto(x - fx, y - fy, x, y) || !plainDuct(x + fx, y + fy)) return false;
        for(int turn = 1; turn <= 3; turn += 2){
            int side = (flow + turn) % 4, sx = Geometry.d4x(side), sy = Geometry.d4y(side);
            if(ground.open(x + sx, y + sy) && !takes(x - sx, y - sy)) return true;
        }
        return false;
    }

    /**
     * Whether a tile is shut in: whether, going over open ground from it, fewer than a handful of tiles
     * can be got to. No line can be laid to or from a place like that, and finding that out the long
     * way takes a whole search.
     * @param shut tiles that count as built on
     * @param except a direction not to set off in from the tile itself, or -1
     */
    public boolean pocket(int x, int y, IntSet shut, int except){
        flood.clear();
        floodSeen.clear();
        floodSeen.add(Point2.pack(x, y));
        for(int d = 0; d < 4; d++){
            if(d == except) continue;
            int nx = x + Geometry.d4x(d), ny = y + Geometry.d4y(d), pos = Point2.pack(nx, ny);
            if(ground.open(nx, ny) && !shut.contains(pos) && floodSeen.add(pos)) flood.addLast(pos);
        }
        int found = 0;
        while(!flood.isEmpty()){
            if(++found >= pocketSize) return false;
            int pos = flood.removeFirst(), px = Point2.x(pos), py = Point2.y(pos);
            for(int d = 0; d < 4; d++){
                int nx = px + Geometry.d4x(d), ny = py + Geometry.d4y(d), next = Point2.pack(nx, ny);
                if(ground.open(nx, ny) && !shut.contains(next) && floodSeen.add(next)) flood.addLast(next);
            }
        }
        return true;
    }

    static final int pocketSize = 60;
    private final IntQueue flood = new IntQueue();
    private final IntSet floodSeen = new IntSet();

    /** Whether a branch could start at this duct, as far as the duct itself and the two on either end of it go. A quick look before the real work. */
    public boolean branches(int x, int y){
        if(router == null || !plainDuct(x, y) || !ground.rule.allows(x, y)) return false;
        int flow = ground.rotation, fx = Geometry.d4x(flow), fy = Geometry.d4y(flow);
        if(!ground.pushesInto(x - fx, y - fy, x, y) || !plainDuct(x + fx, y + fy)) return false;
        for(int turn = 1; turn <= 3; turn += 2){
            int side = (flow + turn) % 4, sx = Geometry.d4x(side), sy = Geometry.d4y(side);
            if(ground.open(x + sx, y + sy) && ground.open(x + sx + fx, y + sy + fy) && ground.open(x + sx * 2, y + sy * 2)) return true;
        }
        return false;
    }

    private boolean plainDuct(int x, int y){
        return ground.look(x, y) && ground.block instanceof Duct found && !found.armored;
    }

    /** Whether something that takes items in stands or will stand on a tile. */
    private boolean takes(int x, int y){
        return ground.look(x, y) && Ground.takesItems(ground.block);
    }

    /**
     * Finds out, once for each search, whether a tile is open, which of the tiles around it would push
     * into a duct or conduit on it, and for conduits which of them make or hold liquid.
     */
    private void look(int x, int y){
        int at = x + y * width;
        if(tileStamp[at] == generation) return;
        tileStamp[at] = generation;
        tileOpen[at] = (byte)(ground.open(x, y) ? 1 : 0);
        int pushed = 0, gives = 0, beside = 0;
        for(int i = 0; i < 4; i++){
            int ox = x + Geometry.d4x(i), oy = y + Geometry.d4y(i);
            if(liquid ? ground.pipesInto(ox, oy, x, y) : ground.pushesInto(ox, oy, x, y)) pushed |= 1 << i;
            if(liquid && ground.look(ox, oy) && Ground.givesLiquid(ground.block)) gives |= 1 << i;
            if(!liquid && ground.look(ox, oy) && (ground.block instanceof Duct || ground.block instanceof DuctRouter || ground.block instanceof OverflowDuct)) beside++;
        }
        tilePushed[at] = (byte)pushed;
        tileGives[at] = (byte)gives;
        tileBeside[at] = (byte)beside;
        tileOre[at] = (byte)(oreGround(x, y) ? 1 : 0);
    }

    /** Whether drills would stand here: ore in the floor, or a vein of ore in a wall right beside. */
    static boolean oreGround(int x, int y){
        Tile tile = world.tile(x, y);
        if(tile == null || tile.solid()) return false;
        if(tile.overlay() != null && tile.overlay().itemDrop != null && !tile.overlay().wallOre) return true;
        for(int i = 0; i < 4; i++){
            Tile near = tile.nearby(i);
            if(near != null && near.solid() && near.build == null && near.overlay() != null && near.overlay().wallOre && near.overlay().itemDrop != null) return true;
        }
        return false;
    }

    /**
     * Whether a duct or conduit on this tile, facing the given way, would be fed by anything other than
     * what is meant to feed it.
     * @param from tiles whose blocks are meant to
     */
    private boolean fed(int x, int y, int facing, IntSet from){
        if(!liquid) return ground.fedByOthers(x, y, facing, from);
        for(int i = 0; i < 4; i++){
            if(i == facing) continue;
            int ox = x + Geometry.d4x(i), oy = y + Geometry.d4y(i);
            if(from.contains(Point2.pack(ox, oy))) continue;
            if(ground.pipesInto(ox, oy, x, y)) return true;
            if(i == (facing + 2) % 4 && ground.look(ox, oy) && Ground.givesLiquid(ground.block)) return true;
        }
        return false;
    }

    private int state(int x, int y, int d){
        return (x + y * width) * 4 + d;
    }

    /**
     * Whether a bridge on this tile would get in the way of another, or another in its way. A bridge
     * links to the first bridge within range in front of it, the way it faces itself, whichever way
     * that one faces; and to nothing that is not straight in front of it. So one put here goes wrong
     * in two ways only. As the far end of a crossing it must find nothing in front of it to link on
     * to. And no bridge that stands behind it, facing it with nothing between, may be there: that one
     * would link to this. Bridges to the side, or facing away, or with another between, are no matter:
     * to keep clear of every bridge within range all round, as was done at first, shuts off most of a
     * base that has many, and lines can then no longer cross each other at all.
     * @param facing the way the bridge would face
     * @param end whether it is the far end of a crossing: what it hands on goes into the tile in front of it
     * @param ignoreX the bridge at the near end of the same crossing, which is meant to link to it
     */
    private boolean bridgeClash(int x, int y, int facing, boolean end, int ignoreX, int ignoreY){
        int range = ((DirectionBridge)bridge).range;
        if(end){
            for(int i = 1; i <= range; i++){
                if(ground.look(x + Geometry.d4x(facing) * i, y + Geometry.d4y(facing) * i) && ground.block == bridge) return true;
            }
        }
        for(int d = 0; d < 4; d++){
            for(int i = 1; i <= range; i++){
                int ox = x - Geometry.d4x(d) * i, oy = y - Geometry.d4y(d) * i;
                if(!ground.look(ox, oy) || ground.block != bridge) continue;
                //the nearest one behind, this way: if it faces here it would link here; if not, it stands between this and any further back
                if(ground.rotation == d && !(ox == ignoreX && oy == ignoreY)) return true;
                break;
            }
        }
        return false;
    }

    private boolean reached(int x, int y, int d, int fx, int fy, Goal goal){
        if(goal.port) return x == goal.portX && y == goal.portY && d == goal.portRotation;
        if(goal.core && world.build(fx, fy) instanceof CoreBlock.CoreBuild core && core.team == ground.team) return true;
        if(goal.into != null && goal.into.contains(Point2.pack(fx, fy))) return true;
        if(goal.lines != null && goal.lines.contains(Point2.pack(fx, fy)) && ground.look(fx, fy)
            && (liquid ? ground.block instanceof mindustry.world.blocks.liquid.Conduit : ground.block instanceof Duct other && !other.armored)){
            //from behind or from the side, never head on
            return !(fx + Geometry.d4x(ground.rotation) == x && fy + Geometry.d4y(ground.rotation) == y);
        }
        return false;
    }

    /** Whether a tile already holds a duct or an end of a bridge of the line that leads up to a state. */
    private boolean onPath(int state, int x, int y){
        int target = x + y * width;
        for(int s = state, steps = 0; s != -1 && steps < 4000; s = parent[s], steps++){
            int tile = s / 4;
            if(tile == target) return true;
            if(jump[s] > 0){
                int d = s % 4;
                if(tile - (Geometry.d4x(d) + Geometry.d4y(d) * width) * jump[s] == target) return true;
            }
        }
        return false;
    }

    /**
     * Whether a line that was found can be built as it is. The search keeps a line from crossing
     * itself as it goes, but a better way to an earlier part of it, found later, can undo that; and
     * two bridges of the same line can get in each other's way. Either is rare, and such a line is dropped.
     */
    private boolean sound(Line line){
        allowed.clear();
        for(Part part : line.parts){
            if(!allowed.add(Point2.pack(part.x, part.y))) return false;
        }
        int range = bridge == null ? 0 : ((DirectionBridge)bridge).range;
        for(int i = 0; i < line.parts.size; i++){
            Part part = line.parts.get(i);
            if(part.block != bridge) continue;
            //the bridge it hands on to, if it is the first of a pair: the next part of the line
            Part partner = i + 1 < line.parts.size && line.parts.get(i + 1).block == bridge && line.parts.get(i + 1).rotation == part.rotation
                && (i == 0 || line.parts.get(i - 1).block != bridge || !pair(line.parts.get(i - 1), part)) ? line.parts.get(i + 1) : null;
            int dx = Geometry.d4x(part.rotation), dy = Geometry.d4y(part.rotation);
            for(int step = 1; step <= range; step++){
                int x = part.x + dx * step, y = part.y + dy * step;
                if(partner != null && partner.x == x && partner.y == y) break;
                //any other bridge of the line in the way would be handed on to instead
                if(line.parts.contains(other -> other.block == bridge && other.x == x && other.y == y)) return false;
            }
        }
        return true;
    }

    /** Whether two bridges of a line, one after the other, are the two ends of one crossing. */
    private boolean pair(Part first, Part second){
        if(first.rotation != second.rotation) return false;
        int dx = second.x - first.x, dy = second.y - first.y;
        return Math.abs(dx) + Math.abs(dy) > 1 && dx * Geometry.d4y(first.rotation) == dy * Geometry.d4x(first.rotation);
    }

    private float left(int x, int y, Goal goal){
        if(goal.port) return Math.abs(x - goal.portX) + Math.abs(y - goal.portY);
        //a little short of the true distance: the thing is some tiles across, and a line that is joined may be nearer still
        if(goal.into != null) return Math.max(Math.abs(x - goal.intoX) + Math.abs(y - goal.intoY) - 4, 0) * (goal.lines == null ? 1f : 0.5f);
        //towards the nearest core, which is also where its other lines run. where it is known how far that is over
        //open ground, that is what the search goes by: on a map of winding caves the straight distance leads it
        //into every dead end on the way. counted a little high, so that it makes for home rather than weighing every side way
        if(homeward != null){
            int far = homeward.get(x, y);
            if(far < 1000000){
                //a line of its own that this one may empty into is as good an end as a core, and usually nearer: without
                //this the search runs on home alongside the line it could have joined, and a base fills up with lines side by side
                float least = far * 1.25f;
                for(int i = 0; i < joinAt.size; i += 2) least = Math.min(least, Math.abs(joinAt.get(i) - x) + Math.abs(joinAt.get(i + 1) - y));
                return least;
            }
        }
        float best = Float.MAX_VALUE;
        for(Building core : cores){
            float half = core.block.size / 2f;
            best = Math.min(best, Math.max(Math.abs(x - core.tileX()) - half, 0f) + Math.max(Math.abs(y - core.tileY()) - half, 0f));
        }
        return best == Float.MAX_VALUE ? 0f : best * (goal.lines == null ? 1f : 0.5f);
    }

    private void visit(int state, int from, float total, int length, Goal goal){
        if(stamp[state] == generation && cost[state] <= total) return;
        boolean fresh = stamp[state] != generation;
        stamp[state] = generation;
        cost[state] = total;
        parent[state] = from;
        jump[state] = (byte)length;
        int tile = state / 4;
        usedStamp[tile] = generation;
        push(state, total + left(tile % width, tile / width, goal), fresh);
    }

    //a small heap of states ordered by cost so far plus what is left; stale entries are skipped when they come up
    private final FloatSeq keys = new FloatSeq();

    private void push(int state, float key, boolean fresh){
        heap.add(state);
        keys.add(key);
        int i = heap.size - 1;
        while(i > 0){
            int up = (i - 1) / 2;
            if(keys.get(up) <= keys.get(i)) break;
            heap.swap(up, i);
            keys.swap(up, i);
            i = up;
        }
    }

    private int pop(){
        int top = heap.get(0);
        int last = heap.size - 1;
        heap.set(0, heap.get(last));
        keys.set(0, keys.get(last));
        heap.size = last;
        keys.size = last;
        int i = 0;
        while(true){
            int a = i * 2 + 1, b = a + 1, least = i;
            if(a < last && keys.get(a) < keys.get(least)) least = a;
            if(b < last && keys.get(b) < keys.get(least)) least = b;
            if(least == i) break;
            heap.swap(least, i);
            keys.swap(least, i);
            i = least;
        }
        return top;
    }

    private @Nullable Line line(int last, int endX, int endY, Goal goal){
        Line out = new Line();
        out.endX = endX;
        out.endY = endY;
        out.joins = !goal.port && !(world.build(endX, endY) instanceof CoreBlock.CoreBuild) && !(goal.into != null && goal.into.contains(Point2.pack(endX, endY)));

        for(int state = last; state != -1; state = parent[state]){
            int tile = state / 4, d = state % 4, x = tile % width, y = tile / width;
            if(jump[state] > 0){
                out.parts.add(new Part(bridge, x, y, d));
                out.parts.add(new Part(bridge, x - Geometry.d4x(d) * jump[state], y - Geometry.d4y(d) * jump[state], d));
            }else{
                out.parts.add(new Part(duct, x, y, d));
                Building wall = world.build(x, y);
                if(wall != null && !out.removals.contains(wall.pos())) out.removals.add(wall.pos());
            }
        }
        out.parts.reverse();
        return sound(out) ? out : null;
    }
}
