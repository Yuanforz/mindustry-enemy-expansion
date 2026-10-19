package expansion.econ;

import java.util.*;

/** The map as the model sees it: where the core is, where the core unit can dig by hand, and the places machines can go. */
public final class Field{
    /** A place a machine of some kind can stand, and what it gives there. */
    public static final class Site{
        public final Recipe.Ground ground;
        public final int x, y, rotation, item;
        public final float factor, distance;
        /** The tiles it covers, as x + y * width, so that two sites that overlap are not both taken. */
        public final int[] tiles;
        /**
         * Whether the side can see it at the start. Ground not yet seen can't be built on, and the core unit sees nothing
         * (its fog radius is 0): it is seen once a unit has been there. (A side the game runs as an AI has no fog.)
         */
        public boolean seen = true;
        /** For drills that reach across the ground to a wall: the open tiles their beams cross, on which nothing solid may stand. */
        public int[] lane = new int[0];
        /** Tiles other such machines must leave free: the row behind it for its duct, or the ring round a floor drill. */
        public int[] apron = new int[0];

        public Site(Recipe.Ground ground, int x, int y, int rotation, int item, float factor, float distance, int[] tiles){
            this.ground = ground;
            this.x = x;
            this.y = y;
            this.rotation = rotation;
            this.item = item;
            this.factor = factor;
            this.distance = distance;
            this.tiles = tiles;
        }

        /**
         * The block it was found for. Two machines on the same kind of ground need not fit the same places: a plasma bore
         * and a large one differ in size and reach. Null where any will do.
         */
        public String block;

        /** Whether this recipe's machine can stand here: the block it was found for, or for a vent any of its size. */
        public boolean fits(Recipe r){
            if(block == null || r.block == null || block.equals(r.block)) return true;
            if(ground == Recipe.Ground.vent){
                var a = mindustry.Vars.content.block(block);
                var b = mindustry.Vars.content.block(r.block);
                return a != null && b != null && a.size == b.size;
            }
            return false;
        }

        @Override
        public String toString(){
            return ground + (item >= 0 ? "/" + item : "") + " " + factor + " at " + x + "," + y + " (" + (int)distance + " tiles)";
        }
    }

    public int coreX, coreY;
    /** For each item, how far from the core the nearest wall the core unit can dig it from is (tiles); infinite when there is none. */
    public float[] handDistance;
    public final List<Site> sites = new ArrayList<>();
    /** How many places were looked at before packing. */
    public int candidates;
    /** Machines already standing on the map at the start. */
    public final List<Machine> existing = new ArrayList<>();
    /**
     * Machines of the map's that stand but give the core nothing (no power, no water, no line): the plan may take one up
     * as a machine of its own that costs nothing but its lines.
     */
    public final List<Machine> revivable = new ArrayList<>();
    /** What the core holds at the start. */
    public float[] stock;

    public Field(Catalog cat){
        handDistance = new float[cat.size()];
        Arrays.fill(handDistance, Float.POSITIVE_INFINITY);
        stock = new float[cat.size()];
    }

    /** Items a second the core unit brings in digging this by hand, counting the flights back when the wall is too far to hand over from. */
    public float handRate(Catalog cat, int item){
        if(!cat.get(item).handMined) return 0f;
        float d = handDistance[item];
        if(Float.isInfinite(d)) return 0f;
        float reach = cat.handOverRange + cat.mineRange;
        if(d <= reach) return cat.mineRate;
        float digging = cat.carry / cat.mineRate, flying = 2f * (d - reach) / cat.flySpeed;
        return cat.carry / (digging + flying);
    }
}
