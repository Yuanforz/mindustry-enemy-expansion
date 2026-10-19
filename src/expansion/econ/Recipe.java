package expansion.econ;

/**
 * What one kind of machine takes and makes, per second, running at full speed. Machines that work the ground (drills,
 * crushers, condensers on vents) make an amount that depends on where they stand: for them {@link #perFactor} is what one
 * unit of the place's yield gives (one ore tile in a beam, one point of sand on a wall face, one ore tile under a drill),
 * and the item comes from the place.
 */
public final class Recipe{
    /** What a machine needs under or in front of it. */
    public enum Ground{
        /** Anywhere. */
        none,
        /** A whole 3x3 vent. */
        vent,
        /** Facing ore in a wall (plasma bores). */
        wallOre,
        /** Facing rock that gives sand (cliff crushers). */
        sandWall,
        /** Standing on ore (impact drills). */
        floorOre
    }

    public final String name, block;
    public final int size;
    public final Ground ground;
    /** Items it costs to build, by {@link Res#id}. */
    public final int[] cost;
    /** The block's build time in ticks, for a builder of speed 1. */
    public final float buildTicks;
    /** Power it uses at full speed, and makes, per second. */
    public final float powerUse, powerOut;
    /** Taken and given per second at full speed, by {@link Res#id}. For machines on the ground, what is given besides the dug item. */
    public final float[] in, out;
    /** For machines on the ground: items a second per unit of yield, for each item they can dig (by {@link Res#id}; 0 if they can't). */
    public final float[] perFactor;
    /** A liquid that speeds it up if it gets some, how much a second, and by how much at most. -1 when there is none. */
    public final int boostRes;
    public final float boostUse, boostMultiplier;
    /** For unit factories and refabricators: the unit made, and the one taken in (-1 for none); one comes out every so many seconds. */
    public final int unitOut, unitIn;
    public final float unitSeconds;
    /** For a module (a whole design built as one): the design. Null for a single block. */
    public Design module;
    /** For a module turned o times, its width and height (a single block: its size both ways). */
    public int width(int o){
        return module == null ? size : module.width(o);
    }

    public int height(int o){
        return module == null ? size : module.height(o);
    }

    public Recipe(String name, String block, int size, Ground ground, int[] cost, float buildTicks, float powerUse, float powerOut,
                  float[] in, float[] out, float[] perFactor, int boostRes, float boostUse, float boostMultiplier,
                  int unitOut, int unitIn, float unitSeconds){
        this.name = name;
        this.block = block;
        this.size = size;
        this.ground = ground;
        this.cost = cost;
        this.buildTicks = buildTicks;
        this.powerUse = powerUse;
        this.powerOut = powerOut;
        this.in = in;
        this.out = out;
        this.perFactor = perFactor;
        this.boostRes = boostRes;
        this.boostUse = boostUse;
        this.boostMultiplier = boostMultiplier;
        this.unitOut = unitOut;
        this.unitIn = unitIn;
        this.unitSeconds = unitSeconds;
    }

    public boolean makesUnits(){
        return unitOut >= 0;
    }

    /** Whether it makes this, wherever it stands (for machines on the ground: whether some place could give it). */
    public boolean makes(int res){
        return out[res] > 0f || perFactor[res] > 0f || unitOut == res;
    }

    public int totalCost(){
        int sum = 0;
        for(int c : cost) sum += c;
        return sum;
    }

    @Override
    public String toString(){
        return name;
    }
}
