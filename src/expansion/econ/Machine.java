package expansion.econ;

/** One machine in the plan or in the plant: what it is, where it stands, and what that place gives it. */
public final class Machine{
    public final Recipe recipe;
    /** For machines on the ground, the item their place gives; -1 otherwise. */
    public final int item;
    /** How much the place gives (ore tiles hit, points of sand, ore tiles under it; 1 for a full vent or no ground at all). */
    public final float factor;
    /** Where it stands, in tiles, and how far that is from the core. */
    public final int x, y;
    public final float distance;
    /** Items it costs: the machine and the line that joins it to the rest. */
    public final int[] cost;
    /** Whether its booster liquid is led to it. */
    public final boolean boosted;
    /** Whether it stood there before anything was planned (part of the base the map comes with). */
    public final boolean existing;
    /** Which way it faces, for machines that face something (drills face their wall). */
    public int rotation;
    /** Whether its place is on ground not yet seen: it can only be begun once a unit has been there. */
    public boolean unseen;

    public Machine(Recipe recipe, int item, float factor, int x, int y, float distance, int[] cost, boolean boosted, boolean existing){
        this.recipe = recipe;
        this.item = item;
        this.factor = factor;
        this.x = x;
        this.y = y;
        this.distance = distance;
        this.cost = cost;
        this.boosted = boosted;
        this.existing = existing;
    }

    /** What it gives per second at full speed, before any boost. */
    public float out(int res){
        float o = recipe.out[res];
        if(res == item) o += recipe.perFactor[res] * factor;
        return o;
    }

    public float powerOut(){
        return recipe.ground == Recipe.Ground.vent ? recipe.powerOut * factor : recipe.powerOut;
    }

    @Override
    public String toString(){
        return recipe.name + (item >= 0 ? "(" + factor + ")" : "") + "@" + x + "," + y;
    }
}
