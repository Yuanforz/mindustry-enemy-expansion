package expansion;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import expansion.Blueprint.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

/** Finds where on the map a blueprint can go. */
public class Fitter{
    /** How many faces a search starts from, nearest home first. Searching is kept short: it happens in the middle of the game. */
    static final int faceLimit = 500;
    /** How far back from the wall a drill may stand. Its beam reaches further, but the ground in between is lost to everything else. */
    static final int gapLimit = 2;

    private final Ground ground;
    private final Survey survey;
    private final Territory territory;

    private final IntSeq near = new IntSeq();
    private final IntSet tried = new IntSet(), around = new IntSet();
    /** Places that have been tried and came to nothing, by the tile of the main block. They are passed over. */
    public final IntSet skip = new IntSet();
    private static final IntSet none = new IntSet();

    public Fitter(Ground ground, Survey survey, Territory territory){
        this.ground = ground;
        this.survey = survey;
        this.territory = territory;
    }

    /**
     * Where a row of drills or crushers goes: against a wall that every one of them gets something out
     * of, as long a row as there is room for up to what is wanted, and as near home as can be.
     * @param item the ore to mine, or null for a row of crushers
     * @param want how many copies of the row would be enough
     * @param reach how far from the nearest core to look, over land
     */
    public @Nullable Layout row(Blueprint blueprint, @Nullable Item item, int want, int reach){
        long prof = Prof.start();
        try{
            return findRow(blueprint, item, want, reach);
        }finally{
            Prof.end("row", prof);
        }
    }

    private @Nullable Layout findRow(Blueprint blueprint, @Nullable Item item, int want, int reach){
        Layout found = findRow(blueprint, item, want, reach, false);
        //how far it looks is where it looks first, not a wall: with no place left nearer, the nearest of what is further out
        //is the best there is (a farther drill costs more line, which the nearest-first order already weighs)
        if(found == null && farOk && reach < Integer.MAX_VALUE / 4 && Clock.now() < deadline) found = findRow(blueprint, item, want, reach, true);
        return found;
    }

    private @Nullable Layout findRow(Blueprint blueprint, @Nullable Item item, int want, int reach, boolean beyond){
        Block main = blueprint.main();
        IntSeq faces = item == null ? survey.sand() : survey.ore(item);
        want = Math.min(want, blueprint.copies);

        //the faces near home, nearest first; or the ones further out than that
        near.clear();
        for(int i = 0; i < faces.size; i++){
            int face = faces.get(i), x = survey.x(face), y = survey.y(face);
            if((territory.fromHome(x, y) <= reach) != beyond && ground.open(x, y) && !avoid.at(x, y)) near.add(face);
        }
        if(near.isEmpty()) return null;
        sortByHome(near);
        if(near.size > faceLimit) near.size = faceLimit;

        tried.clear();
        Layout best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        int gaps = item == null ? 0 : gapLimit;

        for(int i = 0; i < near.size; i++){
            int face = near.get(i), fx = survey.x(face), fy = survey.y(face), d = Survey.direction(face);
            int home = rank(fx, fy);
            Clock.spend(4);
            if((i & 15) == 0 && Clock.now() > deadline) break;
            //nothing further out can beat a full row found nearer
            if(best != null && best.copies >= want && want * 100f - home < bestScore) break;

            for(int gap = 0; gap <= gaps; gap++){
                for(int lane = 0; lane < main.size; lane++){
                    main.nearbySide(0, 0, d, lane, Tmp.p1);
                    //the main block of the first copy, placed so that this lane of it meets the wall at this face
                    int ox = fx + Geometry.d4x(d) * (1 - gap) - Tmp.p1.x, oy = fy + Geometry.d4y(d) * (1 - gap) - Tmp.p1.y;
                    if(!ground.clear(main, ox, oy) || skip.contains(Point2.pack(ox, oy))) continue;

                    for(int flip = 0; flip < 2; flip++){
                        if(!tried.add(((ox + oy * world.width()) * 4 + d) * 2 + flip)) continue;
                        Layout all = blueprint.at(ox, oy, d, flip == 1, blueprint.copies);
                        int count = Math.min(prefix(all, item), want);
                        if(count == 0) continue;

                        //a row that can later be given what makes it faster stands one tile back from the wall, where there is room for that
                        float score = count * 100f - home - (blueprint.boosts.isEmpty() ? gap * 2f : gap == 1 ? 0f : gap == 0 ? 3f : 4f);
                        if(score <= bestScore) continue;
                        Layout layout = count == all.copies ? all : blueprint.at(ox, oy, d, flip == 1, count);
                        if(!valid(layout) || !outlet(layout)) continue;
                        best = layout;
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Whether what a layout puts out can leave it: the tile its line would start on is open. A row whose way out is
     * against a wall, or on something already standing, is no place at all, however much ore it stands on. (With time
     * to look further, the search found such rows first, and a sector's first furnace waited minutes for a place.)
     */
    public boolean outlet(Layout layout){
        Blueprint.Port out = layout.port(Blueprint.Role.out, null);
        return out == null || ground.open(out.x, out.y);
    }

    /** When a search for a place has to stop with the best it has found so far. */
    public long deadline = Long.MAX_VALUE;
    /** Whether a row may go beyond how far it looks, when there is nothing nearer. */
    public boolean farOk = true;

    /** Ground where drills are not to go just now, such as a zone where what was built kept being lost. */
    public interface Avoid{
        boolean at(int x, int y);
    }
    public Avoid avoid = (x, y) -> false;

    /** How many tiles of the ore asked for the last drill fitted by {@link #pad} would stand on. */
    public int lastOre;
    /** How the last search for a place for a drill went. */
    public String padNote = "";

    /**
     * Where a drill that stands on its ore goes: on the patch of floor ore that gives it the most of
     * the item, near home. The ore it mines is whichever there is most of under it, so the item
     * asked for has to be that.
     */
    public @Nullable Layout pad(Blueprint blueprint, Item item, int reach){
        return pad(blueprint, item, reach, 0.5f);
    }

    /** @param share how much of the ground under the drill has to hold the ore for the place to do */
    public @Nullable Layout pad(Blueprint blueprint, Item item, int reach, float share){
        long prof = Prof.start();
        Drill drill = (Drill)blueprint.main();
        IntSeq tiles = survey.floor(item);
        near.clear();
        for(int i = 0; i < tiles.size; i++){
            int x = Point2.x(tiles.get(i)), y = Point2.y(tiles.get(i));
            if(territory.fromHome(x, y) <= reach && ground.open(x, y) && !avoid.at(x, y)) near.add(tiles.get(i));
        }
        Layout best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        tried.clear();
        int looked = 0;
        padNote = tiles.size + " tiles of it, " + near.size + " near enough and open";
        String why = "";
        int rich = 0;

        //drills on a patch go on a grid of their own size, side by side, as people lay them: of the ways such a grid can lie
        //over the ore, the one that puts the most ore under drills that could go up now is kept to, so that the next drill
        //goes beside the last and the patch is not cut up into pieces too small for one
        int size = drill.size;
        int[] byLie = new int[size * size];
        IntIntMap counted = new IntIntMap();
        for(int i = 0; i < near.size && i < 2400; i++){
            int x = Point2.x(near.get(i)), y = Point2.y(near.get(i));
            if(skip.contains(Point2.pack(x, y)) || !ground.clear(drill, x, y)) continue;
            int count = oreUnder(drill, x, y, item);
            if(count < size * size * share) continue;
            counted.put(near.get(i), count);
            byLie[Math.floorMod(x, size) * size + Math.floorMod(y, size)] += count;
        }
        int lie = 0;
        for(int k = 1; k < byLie.length; k++) if(byLie[k] > byLie[lie]) lie = k;

        for(int i = 0; i < near.size && looked < 600; i++){
            int x = Point2.x(near.get(i)), y = Point2.y(near.get(i));
            if(Math.floorMod(x, size) * size + Math.floorMod(y, size) != lie || !counted.containsKey(near.get(i))) continue;
            looked++;
            Clock.spend(4);
            if((looked & 15) == 0 && Clock.now() > deadline) break;
            int count = counted.get(near.get(i), 0);
            rich++;
            float score = count * 10f - territory.near(x, y) * 0.5f;
            if(score <= bestScore) continue;
            boolean fitted = false;
            for(int way = 0; way < 8; way++){
                Layout layout = blueprint.at(x, y, way % 4, way >= 4, 1);
                if(!valid(layout)) continue;
                best = layout;
                bestScore = score;
                lastOre = count;
                fitted = true;
                break;
            }
            //why the first good place would not do, for looking into it
            if(!fitted && why.isEmpty()){
                for(Part part : blueprint.at(x, y, 0, false, 1).parts){
                    if(!ground.clear(part.block, part.x, part.y)) why = "; at " + x + "," + y + " no room for its " + part.block.name;
                    else if(!Build.validPlace(part.block, ground.team, part.x, part.y, part.rotation)) why = "; at " + x + "," + y + " the game refuses its " + part.block.name + (unseen(part.x, part.y) ? ", on ground it has not seen yet" : "");
                    if(!why.isEmpty()) break;
                }
                if(why.isEmpty()) why = "; at " + x + "," + y + " it would stand against something it would feed or be fed by";
            }
        }
        padNote += ", " + looked + " with room for the drill, " + rich + " of those on enough ore" + (best == null ? why : "");
        Prof.end("pad", prof);
        return best;
    }

    /**
     * A place something would have gone in the last round of working things out, had the ground there
     * been seen: the game lets nothing be built on ground a side has not laid eyes on. -1 if there was none.
     */
    public int unseenAt = -1;

    /** Whether a tile is ground the side has not seen yet, on a map where what has not been seen can't be built on. */
    public boolean unseen(int x, int y){
        return state.rules.fog && state.rules.staticFog && !fogControl.isDiscovered(ground.team, x, y);
    }

    /** How many of the tiles a drill would stand on hold the item, or 0 if it would mine something else there. */
    private int oreUnder(Drill drill, int x, int y, Item item){
        int offset = -(drill.size - 1) / 2, wanted = 0, other = 0;
        for(int dx = 0; dx < drill.size; dx++){
            for(int dy = 0; dy < drill.size; dy++){
                Tile tile = world.tile(x + dx + offset, y + dy + offset);
                if(tile == null || !drill.canMine(tile)) continue;
                if(drill.getDrop(tile) == item) wanted++; else other++;
            }
        }
        return wanted > other ? wanted : 0;
    }

    /**
     * What the places for a row are taken in order of, if not how near home they are: for a factory's
     * crushers, how near what else the factory is fed with goes by.
     */
    public @Nullable Lines.Far order;

    private int rank(int x, int y){
        return order != null ? order.get(x, y) : territory.near(x, y);
    }

    private void sortByHome(IntSeq faces){
        //few enough for this
        int[] items = faces.items;
        long[] keyed = new long[faces.size];
        for(int i = 0; i < faces.size; i++) keyed[i] = ((long)Math.min(rank(survey.x(items[i]), survey.y(items[i])), 1 << 30) << 32) | (items[i] & 0xffffffffL);
        java.util.Arrays.sort(keyed);
        for(int i = 0; i < faces.size; i++) items[i] = (int)keyed[i];
    }

    /** How many copies of a row, counted from the first, stand on open ground with every drill and crusher fully at work. */
    private int prefix(Layout all, @Nullable Item item){
        int each = all.blueprint.parts.size;
        for(int copy = 0; copy < all.copies; copy++){
            for(int i = copy * each; i < (copy + 1) * each; i++){
                Part part = all.parts.get(i);
                if(!ground.clear(part.block, part.x, part.y) || !works(part, item)) return copy;
            }
        }
        return all.copies;
    }

    /** Whether a part that takes something out of a wall gets it from every tile of wall it faces. */
    private boolean works(Part part, @Nullable Item item){
        if(part.block instanceof BeamDrill drill){
            return beams(drill, part.x, part.y, part.rotation, item) >= enough(drill);
        }else if(part.block instanceof WallCrafter crusher){
            for(int lane = 0; lane < crusher.size; lane++){
                crusher.nearbySide(part.x, part.y, part.rotation, lane, Tmp.p1);
                Tile wall = world.tile(Tmp.p1.x, Tmp.p1.y);
                if(wall == null || !wall.block().isStatic() || wall.block().attributes.get(crusher.attribute) <= 0f) return false;
            }
        }
        return true;
    }

    /**
     * How many of a wall drill's beams have to end on ore for it to be worth putting up. Ore shows in
     * walls a few tiles at a time: a small drill is put where all of it works, a wide one where most of it does.
     */
    public static int enough(BeamDrill drill){
        return drill.size <= 2 ? drill.size : drill.size - 1;
    }

    /** How many of a drill's beams would end on the given ore (any it can mine, if none is given), over ground that is open. */
    public int beams(BeamDrill drill, int x, int y, int rotation, @Nullable Item item){
        int dx = Geometry.d4x(rotation), dy = Geometry.d4y(rotation), count = 0;
        for(int lane = 0; lane < drill.size; lane++){
            drill.nearbySide(x, y, rotation, lane, Tmp.p1);
            int startX = Tmp.p1.x, startY = Tmp.p1.y;
            for(int i = 0; i < drill.range; i++){
                Tile other = world.tile(startX + dx * i, startY + dy * i);
                if(other == null) break;
                if(other.solid()){
                    Item drop = other.build != null ? null : other.wallDrop();
                    if(drop != null && drop.hardness <= drill.tier && (item == null || drop == item) && (drill.blockedItems == null || !drill.blockedItems.contains(drop))) count++;
                    break;
                }
                //what is planned in the way of the beam will stand there
                if(ground.planned(other.x, other.y) != null) break;
            }
        }
        return count;
    }

    /**
     * Whether a layout can be built as it is: the game allows every part, and it neither feeds nor is fed
     * by anything it stands next to. What makes items hands them to whatever is beside it, so a drill
     * next to someone else's duct would put its ore in a line that was not laid for it.
     */
    public boolean valid(Layout layout){
        long prof = Prof.start();
        try{
            return check(layout);
        }finally{
            Prof.end("valid", prof);
        }
    }

    private boolean check(Layout layout){
        around.clear();
        //the quick look at every part first: asking the game is the slow part
        for(Part part : layout.parts){
            if(!ground.clear(part.block, part.x, part.y)) return false;
        }
        for(Part part : layout.parts){
            if(!ground.fits(part.block, part.x, part.y, part.rotation)){
                //room enough, and the game will not have it: on a map with fog that may only be that the ground has not been seen
                if(unseenAt == -1 && unseen(part.x, part.y)) unseenAt = Point2.pack(part.x, part.y);
                return false;
            }
            world.tile(part.x, part.y).getLinkedTilesAs(part.block, tile -> around.add(tile.pos()));
        }
        for(Port port : layout.ports) around.add(Point2.pack(port.x, port.y));

        for(Part part : layout.parts){
            boolean makes = Ground.makesItems(part.block), takes = Ground.takesItems(part.block);
            if(!makes && !takes) continue;
            int offset = -(part.block.size - 1) / 2, size = part.block.size;
            for(int i = 0; i < size; i++){
                for(int side = 0; side < 4; side++){
                    //the tile outside the block, next to the i-th tile of this side
                    int x = part.x + offset + (side == 0 ? size : side == 2 ? -1 : i), y = part.y + offset + (side == 1 ? size : side == 3 ? -1 : i);
                    int ix = part.x + offset + (side == 0 ? size - 1 : side == 2 ? 0 : i), iy = part.y + offset + (side == 1 ? size - 1 : side == 3 ? 0 : i);
                    if(around.contains(Point2.pack(x, y))) continue;
                    //a duct of the layout faces what it feeds, and may: that is what ports are for
                    if(takes && ground.pushesInto(x, y, ix, iy) && !(part.block.rotate && part.block.isDuct && Geometry.d4x(part.rotation) == x - ix && Geometry.d4y(part.rotation) == y - iy)) return false;
                    if(makes && ground.look(x, y) && Ground.takesItems(ground.block)) return false;
                }
            }
        }
        return true;
    }
}
