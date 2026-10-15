package expansion;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.type.*;
import mindustry.world.blocks.distribution.*;

import static mindustry.Vars.*;

/**
 * What the enemy knows about the supply lines it has laid: where items get onto them, and where they
 * are taken off again.
 *
 * Only those two ends are kept. Where a line runs is read off the map each time, by following its
 * ducts from the start, so the picture stays true when the player cuts a line or the game is loaded.
 */
public class Net{
    /** Something on the enemy's lines. */
    public static class Thing{
        public final Kind kind;
        /** For what puts items on a line, the first tile of that line. For a branch, its first router. For the rest, their main block. */
        public final int x, y;
        public final @Nullable Item item;
        /** For what makes a liquid, and for a conduit that takes some of it away. */
        public final @Nullable Liquid liquid;
        /** Items or units of liquid a second: made, for what feeds a line; wanted, for a branch or a conduit. */
        public final float rate;

        public Thing(Kind kind, int x, int y, @Nullable Item item, float rate){
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.item = item;
            this.liquid = null;
            this.rate = rate;
        }

        public Thing(Kind kind, int x, int y, Liquid liquid, float rate){
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.item = null;
            this.liquid = liquid;
            this.rate = rate;
        }

        public int pos(){
            return Point2.pack(x, y);
        }
    }

    public enum Kind{
        /** A row of drills, putting ore on a line. */
        mine('m'),
        /** A factory, putting what it makes on a line. */
        maker('f'),
        /** A row of crushers. Their sand goes straight to a furnace and never onto a line. */
        quarry('q'),
        /** Where one item is taken off a line for something. */
        branch('b'),
        yard('y'),
        post('p'),
        /** Something that makes a liquid. The place is the block that gives it off. */
        spring('s'),
        /** A conduit that takes liquid from a block. The place is that block's. */
        pipe('l');

        final char code;

        Kind(char code){
            this.code = code;
        }
    }

    public final Seq<Thing> things = new Seq<>();
    /**
     * What has been seen passing over the ducts of the base the enemy started with: for each tile, a bit
     * for every item. Those lines were laid by whoever made the map, and nothing says what they carry,
     * so they are watched.
     */
    private final IntIntMap watched = new IntIntMap();
    private final Ground ground;
    private final IntSeq path = new IntSeq();
    private final IntSet seen = new IntSet();

    public Net(Ground ground){
        this.ground = ground;
    }

    public void clear(){
        things.clear();
        watched.clear();
        fresh();
    }

    //what was worked out about the lines in the course of one round of planning: following every line again for every question is slow
    private final ObjectMap<Item, IntFloatMap> carriedNow = new ObjectMap<>();
    private @Nullable IntIntMap mixNow;

    /** Forgets what was worked out about the lines: called when a new round of planning starts, and when something is added. */
    public void fresh(){
        carriedNow.clear();
        mixNow = null;
        wholeNow.clear();
    }

    /** Notes what is on a duct at the moment. */
    public void watch(int pos, Item item){
        if(item.id < 31) watched.put(pos, watched.get(pos, 0) | (1 << item.id));
    }

    /**
     * Where an item has been seen on the lines the enemy's base came with, and everywhere downstream
     * of there. How much goes by is not known.
     */
    public IntSet seen(Item item){
        IntSet out = new IntSet();
        if(item.id >= 31) return out;
        long prof = Prof.start();
        IntSeq from = new IntSeq();
        for(var entry : watched){
            if((entry.value & (1 << item.id)) != 0) from.add(entry.key);
        }
        for(int i = 0; i < from.size; i++){
            int pos = from.get(i);
            if(out.contains(pos)) continue;
            IntSeq tiles = trace(Point2.x(pos), Point2.y(pos));
            for(int j = 0; j < tiles.size; j++){
                //the rest of the way has been walked already
                if(!out.add(tiles.get(j))) break;
            }
        }
        Prof.end("seen", prof);
        return out;
    }

    public int count(Kind kind, @Nullable Item item){
        return things.count(thing -> thing.kind == kind && (item == null || thing.item == item));
    }

    /**
     * The tiles of a line, from the given one to where it ends, in the order items pass over them.
     * What is planned counts as standing. The line ends at the first thing that is not a duct.
     */
    public IntSeq trace(int x, int y){
        path.clear();
        seen.clear();
        while(ground.look(x, y) && seen.add(Point2.pack(x, y))){
            int d = ground.rotation, dx = Geometry.d4x(d), dy = Geometry.d4y(d);
            if(ground.block instanceof Duct || ground.block instanceof DuctRouter || ground.block instanceof OverflowDuct){
                path.add(Point2.pack(x, y));
                x += dx;
                y += dy;
            }else if(ground.block instanceof DuctBridge bridge){
                path.add(Point2.pack(x, y));
                //a bridge hands on to the next bridge in front of it, if there is one in range
                int range = bridge.range, hop = 1;
                for(int i = 1; i <= range; i++){
                    if(ground.look(x + dx * i, y + dy * i) && ground.block instanceof DuctBridge){
                        hop = i;
                        break;
                    }
                }
                x += dx * hop;
                y += dy * hop;
            }else{
                break;
            }
        }
        endX = x;
        endY = y;
        return path;
    }

    /** Where the last line that was followed ended: the tile after its last duct. */
    public int endX, endY;

    private final ObjectMap<Thing, Boolean> wholeNow = new ObjectMap<>();

    /**
     * Whether what a mine or a factory puts on its line still gets to a core. It does not when the
     * player has cut the line and holds the place, or built across the way before the line was finished:
     * such a source brings nothing in, and is not one to count on until its line has been led on again.
     */
    public boolean whole(Thing source){
        Boolean known = wholeNow.get(source);
        if(known != null) return known;
        trace(source.x, source.y);
        //to a core; or, for a line laid for one thing alone, into that thing, which takes what it carries
        boolean whole = ground.look(endX, endY) && (ground.block instanceof mindustry.world.blocks.storage.CoreBlock
            || (source.item != null && !(ground.block instanceof Duct) && ground.block.consumesItem(source.item)));
        wholeNow.put(source, whole);
        return whole;
    }

    /**
     * Where on the enemy's lines an item can be had, and how much of it a second: what is put on upstream
     * of each tile, less what is already taken off for something else.
     */
    public IntFloatMap carried(Item item){
        IntFloatMap known = carriedNow.get(item);
        if(known != null) return known;
        long prof = Prof.start();
        IntFloatMap out = new IntFloatMap();
        carriedNow.put(item, out);
        for(Thing source : things){
            if((source.kind != Kind.mine && source.kind != Kind.maker) || source.item != item) continue;
            along(trace(source.x, source.y), item, source.rate, out);
        }
        //and the lines that were led off those for something: the next thing along can be supplied from the end of the
        //last, which is how a row of turrets is fed, instead of each from far back
        for(Thing branch : things){
            if(branch.kind != Kind.branch || branch.item != item) continue;
            int start = spur(branch.x, branch.y);
            if(start == -1) continue;
            IntSeq tiles = trace(Point2.x(start), Point2.y(start));
            //only a line to a turret has anything to spare: a turret uses what it gets when it fires, and the line stands full
            //the rest of the time. a furnace or a yard uses all that was led off for it, all the time
            boolean spare = ground.look(endX, endY) && ground.block instanceof mindustry.world.blocks.defense.turrets.BaseTurret;
            if(spare) along(tiles, item, branch.rate, out);
        }
        Prof.end("carried", prof);
        return out;
    }

    private void along(IntSeq tiles, Item item, float left, IntFloatMap out){
        for(int i = 0; i < tiles.size; i++){
            int pos = tiles.get(i);
            Thing taken = things.find(thing -> thing.kind == Kind.branch && thing.item == item && thing.pos() == pos);
            if(taken != null) left = Math.max(left - taken.rate, 0f);
            out.increment(pos, 0f, left);
        }
    }

    /**
     * Where the line that was led off at a router begins: the router set to its item, if it is sorted
     * out of a mixed line, or the first duct at the router's side.
     * @return the packed position, or -1 if there is no such line there
     */
    public int spur(int x, int y){
        if(!ground.look(x, y) || !(ground.block instanceof DuctRouter || ground.block instanceof OverflowDuct)) return -1;
        int flow = ground.rotation;
        for(int turn = 1; turn <= 3; turn += 2){
            int side = (flow + turn) % 4, sx = x + Geometry.d4x(side), sy = y + Geometry.d4y(side);
            if(!ground.look(sx, sy)) continue;
            if(ground.block instanceof DuctRouter && ground.rotation == side) return Point2.pack(sx, sy);
            if((ground.block instanceof Duct || ground.block instanceof DuctBridge) && ground.rotation != (side + 2) % 4) return Point2.pack(sx, sy);
        }
        return -1;
    }

    /**
     * What each tile of the lines that run to a core carries: a bit for every item that is put on
     * upstream of it. A line with one bit set carries one thing only, and can be shared out with a
     * plain router; a mixed one has to be sorted.
     */
    public IntIntMap mix(){
        if(mixNow != null) return mixNow;
        IntIntMap out = mixNow = new IntIntMap();
        for(Thing source : things){
            if((source.kind != Kind.mine && source.kind != Kind.maker) || source.item == null) continue;
            int bit = source.item.id < 31 ? 1 << source.item.id : 1 << 31;
            IntSeq tiles = trace(source.x, source.y);
            for(int i = 0; i < tiles.size; i++) out.put(tiles.get(i), out.get(tiles.get(i), 0) | bit);
        }
        return out;
    }

    /** Every tile of every line the enemy has laid that runs on to a core, with how many items a second pass over it. */
    public IntFloatMap lines(){
        IntFloatMap out = new IntFloatMap();
        for(Thing source : things){
            //one that was cut runs on to nothing: a line led into it would go nowhere
            if((source.kind != Kind.mine && source.kind != Kind.maker) || !whole(source)) continue;
            IntSeq tiles = trace(source.x, source.y);
            for(int i = 0; i < tiles.size; i++) out.increment(tiles.get(i), 0f, source.rate);
        }
        return out;
    }

    /** How much of a liquid is already taken by conduit from the block at a place. */
    public float piped(int x, int y, Liquid liquid){
        float total = 0f;
        for(Thing thing : things){
            if(thing.kind == Kind.pipe && thing.x == x && thing.y == y && thing.liquid == liquid) total += thing.rate;
        }
        return total;
    }

    public String write(){
        StringBuilder out = new StringBuilder();
        for(Thing thing : things){
            if(out.length() > 0) out.append(',');
            String what = thing.item != null ? thing.item.name : thing.liquid != null ? thing.liquid.name : "-";
            out.append(thing.kind.code).append(':').append(thing.x).append(':').append(thing.y).append(':').append(what).append(':').append(Math.round(thing.rate * 100f));
        }
        return out.toString();
    }

    public void read(String text){
        things.clear();
        for(String entry : text.split(",")){
            String[] parts = entry.split(":");
            if(parts.length != 5 || parts[0].length() != 1) continue;
            Kind kind = Structs.find(Kind.values(), k -> k.code == parts[0].charAt(0));
            if(kind == null) continue;
            int x = Strings.parseInt(parts[1], 0), y = Strings.parseInt(parts[2], 0);
            float rate = Strings.parseInt(parts[4], 0) / 100f;
            Liquid liquid = content.item(parts[3]) == null ? content.liquid(parts[3]) : null;
            things.add(liquid != null ? new Thing(kind, x, y, liquid, rate) : new Thing(kind, x, y, content.item(parts[3]), rate));
        }
    }
}
